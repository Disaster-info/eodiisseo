package com.disasterinfo.eodiisseo.ai.resource.vector;
import com.disasterinfo.eodiisseo.ai.resource.*;
import com.disasterinfo.eodiisseo.ai.resource.adapter.KoreanLexicalParagraphSearch;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.port.*;
import com.disasterinfo.eodiisseo.ai.chat.port.*;
import java.time.*;
import java.util.*;
/** Opt-in construction only, not a bean/controller. Existing ResourceService remains the final approval/text gate. */
public final class ApprovedRagPipeline {
    public enum Mode { LEXICAL, EXACT }
    private final OfficialResourceStore resources;private final ResourceApprovalPort approvals;private final RagChunkGroup.PlanReview plans;
    private final RagVectorStore vectors;private final EmbeddingPort embeddings;private final EmbeddingPort.Model model;
    private final SemanticGuidanceScope semantic=new SemanticGuidanceScope();
    private final double maximumDistance;private final Clock clock;private final KoreanLexicalParagraphSearch lexical=new KoreanLexicalParagraphSearch();
    public ApprovedRagPipeline(OfficialResourceStore resources,ResourceApprovalPort approvals,RagChunkGroup.PlanReview plans,
            RagVectorStore vectors,EmbeddingPort embeddings,EmbeddingPort.Model model,Clock clock){
        this(resources,approvals,plans,vectors,embeddings,model,clock,0.0);
    }
    /** Distance calibration is explicit and development-only; default zero is deliberately fail-closed. */
    public ApprovedRagPipeline(OfficialResourceStore resources,ResourceApprovalPort approvals,RagChunkGroup.PlanReview plans,
            RagVectorStore vectors,EmbeddingPort embeddings,EmbeddingPort.Model model,Clock clock,double maximumDistance){
        if(!Double.isFinite(maximumDistance)||maximumDistance<0||maximumDistance>1)throw new IllegalArgumentException("DISTANCE_POLICY");this.maximumDistance=maximumDistance;
        this.resources=Objects.requireNonNull(resources);this.approvals=Objects.requireNonNull(approvals);this.plans=Objects.requireNonNull(plans);
        this.vectors=Objects.requireNonNull(vectors);this.embeddings=Objects.requireNonNull(embeddings);this.model=Objects.requireNonNull(model);this.clock=Objects.requireNonNull(clock);
    }
    private String generic(String hazard){return switch(hazard){case "EARTHQUAKE"->"지진 행동요령";case "HEAVY_RAIN"->"호우 행동요령";case "WILDFIRE"->"산불 행동요령";default->throw new IllegalArgumentException("HAZARD_REQUIRED");};}
    private boolean eligible(RagChunkGroup group){
        try{
            var doc=resources.findById(group.resourceId()).orElseThrow();group.verify(doc);
            if(!plans.approved(group.id(),group.fingerprint(),group.manifestHash(),clock.instant()))return false;
            var query=semantic.prepare(generic(group.hazard())).orElseThrow();
            if(!scopeMatches(group,doc,query))return false;
            return gate(List.of(doc),Map.of(doc.getResourceId(),group),generic(group.hazard())).resources().size()==1;
        }catch(RuntimeException invalid){return false;}
    }
    private boolean scopeMatches(RagChunkGroup group,OfficialResourceDocument doc,ResourceParagraphSearch.Query query){
        return query.topic().equals(group.hazard())&&semantic.accepts(group,doc);
    }
    private ChatOfficialResourceSearchPort.SearchResult gate(List<OfficialResourceDocument> docs,Map<UUID,RagChunkGroup> selected,String question){
        var store=new OfficialResourceStore(){
            public boolean isAvailable(){return resources.isAvailable();}
            public Optional<OfficialResourceDocument> findById(UUID id){return resources.findById(id);}
            public OfficialResourceDocument append(OfficialResourceDocument d){throw new UnsupportedOperationException();}
            public List<OfficialResourceDocument> searchCandidates(List<String> terms,int limit){return docs;}
        };
        var paragraphs=new ResourceParagraphSearch(){
            public Optional<Query> prepare(String q){return semantic.prepare(q);}
            public List<String> select(Query q,OfficialResourceDocument d){
                var group=selected.get(d.getResourceId());if(group==null||!q.topic().equals(group.hazard()))return List.of();
                group.verify(d);if(!plans.approved(group.id(),group.fingerprint(),group.manifestHash(),clock.instant()))return List.of();return scopeMatches(group,d,q)?group.texts():List.of();
            }
        };
        return new ResourceService(store,paragraphs,Optional.of(approvals),clock).search(question);
    }
    /** Synchronous durable claim/retry, with no scheduler or unapproved automatic backfill. */
    public String index(RagChunkGroup group,Duration budget){
        if(budget==null||budget.isNegative()||budget.isZero()||budget.compareTo(Duration.ofSeconds(10))>0)throw new IllegalArgumentException("INDEX_BUDGET");
        if(!embeddings.configured())return "NETWORK_EMBEDDING_DISABLED";
        if(!vectors.available())return "VECTOR_BACKEND_UNAVAILABLE";
        if(!eligible(group))return "UNAPPROVED_OR_INVALID_SOURCE";
        var claim=vectors.claim(group,model);if(!"CLAIMED".equals(claim.state()))return claim.state();
        try{
            Instant deadline=clock.instant().plus(budget);var results=new ArrayList<double[]>();
            for(var paragraph:group.paragraphs()){
                if(!eligible(group))throw new IllegalStateException("APPROVAL_CHANGED");
                results.add(EmbeddingPort.generate(embeddings,new EmbeddingPort.Request(model,EmbeddingPort.Purpose.DOCUMENT,paragraph.text(),false,deadline),clock));
            }
            vectors.commit(group,model,claim,results,()->eligible(group));return "READY";
        }catch(RuntimeException invalid){vectors.fail(group,model,claim,"INDEXING_FAILED");return "INDEXING_FAILED";}
    }
    public record Bundle(ChatOfficialResourceSearchPort search,ChatAnswerGenerationPort generation) {}
    /** Explicit paired activation prevents configuring exact retrieval without its context-generation guard. */
    public Bundle configure(Mode mode,ChatAnswerGenerationPort generation){
        Objects.requireNonNull(generation);
        return new Bundle(select(mode),mode==Mode.EXACT?new MandatoryContextGenerationGuard(generation):generation);
    }
    private ChatOfficialResourceSearchPort select(Mode mode){
        if(mode==null||mode==Mode.LEXICAL)return new ResourceService(resources,lexical,Optional.of(approvals),clock);
        if(!embeddings.configured()||!vectors.available())throw new IllegalStateException("SEMANTIC_MODE_UNAVAILABLE");
        return new ChatEvidenceGenerationPolicy(){
            public SearchResult search(String question){return exact(question);}
            public ChatAnswerGenerationPort guardGeneration(ChatAnswerGenerationPort delegate){return new MandatoryContextGenerationGuard(delegate);}
        };
    }
    private ChatOfficialResourceSearchPort.SearchResult empty(){return new ChatOfficialResourceSearchPort.SearchResult(List.of(),false);}
    private ChatOfficialResourceSearchPort.SearchResult exact(String question){
        try{
            var query=semantic.prepare(question);if(query.isEmpty())return empty();
            // No query embedding if all candidate documents/plans are unapproved or stale.
            var allowed=vectors.eligible(query.get().topic(),model,clock.instant()).stream().filter(this::eligible).toList();if(allowed.isEmpty())return empty();
            var validIds=new HashSet<UUID>();allowed.forEach(g->validIds.add(g.id()));
            double[] vector=EmbeddingPort.generate(embeddings,new EmbeddingPort.Request(model,EmbeddingPort.Purpose.QUERY,question,false,clock.instant().plusSeconds(2)),clock);
            var docs=new ArrayList<OfficialResourceDocument>();var selected=new LinkedHashMap<UUID,RagChunkGroup>();
            for(var hit:vectors.search(query.get().topic(),model,vector,clock.instant(),60)){
                if(hit.distance()>maximumDistance)continue;
                if(!validIds.contains(hit.group()))return empty();
                var group=vectors.group(hit.group(),model).orElseThrow();
                if(group.paragraphs().stream().noneMatch(p->p.id().equals(hit.chunk())))return empty();
                if(!group.hazard().equals(query.get().topic())||!eligible(group))return empty();
                var doc=resources.findById(group.resourceId()).orElseThrow();group.verify(doc);
                if(!scopeMatches(group,doc,query.get()))continue;
                var previous=selected.putIfAbsent(doc.getResourceId(),group);
                if(previous!=null&&!previous.id().equals(group.id()))return empty(); // Never silently discard another mandatory group.
                if(previous==null)docs.add(doc);
            }
            // Reload source records immediately before the existing ResourceService's final validation.
            docs=docs.stream().map(d->resources.findById(d.getResourceId()).orElseThrow()).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return gate(docs,selected,question);
        }catch(RuntimeException invalid){return empty();}
    }
}
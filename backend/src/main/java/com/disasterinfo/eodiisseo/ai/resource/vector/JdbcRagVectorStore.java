package com.disasterinfo.eodiisseo.ai.resource.vector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.json.JsonMapper;
/** Explicit sidecar repository only. Never creates tables/extensions or registers a runtime bean. */
public final class JdbcRagVectorStore implements RagVectorStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate sql;private final TransactionTemplate tx;private final Backend backend;private final Clock clock;
    public JdbcRagVectorStore(JdbcTemplate sql,PlatformTransactionManager manager,Backend backend,Clock clock){this.sql=sql;tx=new TransactionTemplate(manager);this.backend=backend;this.clock=Objects.requireNonNull(clock);}
    public boolean available(){return backend.available()&&Boolean.TRUE.equals(sql.queryForObject("select to_regclass('rag_vector_group') is not null and to_regclass('rag_vector_work') is not null",Boolean.class));}
    /** Approval predicates run BEFORE ranking. Actual document fingerprint/source/paragraphs are rechecked by ResourceService. */
    public static final String ELIGIBLE="""
        from rag_vector_chunk c join rag_vector_group g on g.group_id=c.group_id and g.model_key=c.model_key
        join rag_vector_work w on w.group_id=g.group_id and w.model_key=g.model_key
        join official_resource r on r.resource_id=g.resource_id
        join ai_resource_review a on a.resource_id=r.resource_id
        left join owned_resource_import i on i.resource_id=r.resource_id
        where g.hazard_type=? and c.model_key=? and w.state='READY'
        and a.approved_public_ai_evidence and a.injection_reviewed and a.revoked_at is null
        and a.revision_fingerprint=g.revision_fingerprint and c.revision_fingerprint=g.revision_fingerprint
        and a.source_url=r.source_url and a.source_organization=r.agency_name
        and a.reviewed_at<=? and a.valid_until>?
        and (i.resource_id is null or (i.superseded_at is null and i.expires_at>?))
        """;
    public static Object[] filters(String hazard,EmbeddingPort.Model model,Instant now){return new Object[]{hazard,model.key(),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now)};}
    public Claim claim(RagChunkGroup group,EmbeddingPort.Model model){
        if(!available())throw new IllegalStateException("VECTOR_BACKEND_UNAVAILABLE");
        return tx.execute(status->{
            sql.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","rag:"+group.id()+":"+model.key());
            var rows=sql.queryForList("select * from rag_vector_work where group_id=? and model_key=? for update",group.id(),model.key());
            Instant now=clock.instant();int attempts=0;
            if(!rows.isEmpty()){
                var old=rows.getFirst();if(!group.manifestHash().equals(old.get("manifest_hash")))throw new IllegalArgumentException("MANIFEST_CONFLICT");
                if("READY".equals(old.get("state")))return new Claim(null,"DUPLICATE");
                if("WORKING".equals(old.get("state"))&&((Timestamp)old.get("lease_until")).toInstant().isAfter(now))return new Claim(null,"BUSY");
                attempts=((Number)old.get("attempts")).intValue();if(attempts>=3)return new Claim(null,"RETRY_LIMIT");
            }
            UUID token=UUID.randomUUID();
            sql.update("""
                insert into rag_vector_work(group_id,model_key,resource_id,manifest_hash,state,claim_id,lease_until,attempts)
                values(?,?,?,?,'WORKING',?,?,?) on conflict(group_id,model_key) do update set state='WORKING',
                claim_id=excluded.claim_id,lease_until=excluded.lease_until,attempts=excluded.attempts,error_code=null
                """,group.id(),model.key(),group.resourceId(),group.manifestHash(),token,Timestamp.from(now.plusSeconds(30)),attempts+1);
            return new Claim(token,"CLAIMED");
        });
    }
    public void commit(RagChunkGroup group,EmbeddingPort.Model model,Claim claim,List<double[]> values,BooleanSupplier approved){
        if(values.size()!=group.paragraphs().size())throw new IllegalArgumentException("INCOMPLETE_VECTOR_GROUP");
        values.forEach(v->EmbeddingPort.vector(model,v));
        tx.executeWithoutResult(status->{
            var work=sql.queryForList("select claim_id,lease_until,state from rag_vector_work where group_id=? and model_key=? for update",group.id(),model.key());
            if(work.isEmpty()||!"WORKING".equals(work.getFirst().get("state"))||!Objects.equals(claim.token(),work.getFirst().get("claim_id"))
                ||!((Timestamp)work.getFirst().get("lease_until")).toInstant().isAfter(clock.instant()))throw new IllegalStateException("INDEX_LEASE_LOST");
            sql.queryForList("select resource_id from official_resource where resource_id=? for update",group.resourceId());
            sql.queryForList("select resource_id from ai_resource_review where resource_id=? for update",group.resourceId());
            if(!approved.getAsBoolean())throw new IllegalStateException("APPROVAL_CHANGED");
            Instant now=clock.instant();if(!((Timestamp)work.getFirst().get("lease_until")).toInstant().isAfter(now))throw new IllegalStateException("INDEX_LEASE_LOST");
            sql.update("""
                insert into rag_vector_group(group_id,model_key,resource_id,revision_fingerprint,manifest_hash,hazard_type,manifest_json,
                    embedding_model,embedding_model_digest,embedding_dimension,input_version,max_tokens,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,group.id(),model.key(),group.resourceId(),group.fingerprint(),group.manifestHash(),group.hazard(),JSON.writeValueAsString(group),
                    model.id(),model.digest(),model.dimension(),model.inputVersion(),model.maxTokens(),Timestamp.from(now));
            for(int n=0;n<group.paragraphs().size();n++){
                var p=group.paragraphs().get(n);
                sql.update("""
                    insert into rag_vector_chunk(chunk_id,group_id,model_key,resource_id,revision_fingerprint,paragraph_index,paragraph_text,
                        paragraph_hash,source_locator,section_path,mandatory_context_group,extraction_version)
                    values(?,?,?,?,?,?,?,?,?,?,?,?)
                    """,p.id(),group.id(),model.key(),group.resourceId(),group.fingerprint(),p.ordinal(),p.text(),p.hash(),p.locator(),JSON.writeValueAsString(group.sectionPath()),group.id(),group.extractionVersion());
                backend.put(p.id(),model,values.get(n));
            }
            if(!((Timestamp)work.getFirst().get("lease_until")).toInstant().isAfter(clock.instant())||!approved.getAsBoolean())throw new IllegalStateException("INDEX_LEASE_OR_APPROVAL_LOST");
            sql.update("update rag_vector_work set state='READY',claim_id=null,lease_until=null,error_code=null where group_id=? and model_key=? and claim_id=?",group.id(),model.key(),claim.token());
        });
    }
    public void fail(RagChunkGroup group,EmbeddingPort.Model model,Claim claim,String code){
        sql.update("update rag_vector_work set state='FAILED',error_code=?,claim_id=null,lease_until=null where group_id=? and model_key=? and claim_id=? and state='WORKING'",code,group.id(),model.key(),claim.token());
    }
    public Optional<RagChunkGroup> group(UUID id,EmbeddingPort.Model model){
        var found=sql.queryForList("select * from rag_vector_group where group_id=? and model_key=?",id,model.key());if(found.isEmpty())return Optional.empty();var row=found.getFirst();
        if(!model.id().equals(row.get("embedding_model"))||!model.digest().equals(row.get("embedding_model_digest"))
            ||model.dimension()!=((Number)row.get("embedding_dimension")).intValue()||!model.inputVersion().equals(row.get("input_version"))
            ||model.maxTokens()!=((Number)row.get("max_tokens")).intValue())throw new IllegalStateException("STORED_MODEL_MISMATCH");
        var group=JSON.readValue((String)row.get("manifest_json"),RagChunkGroup.class);
        if(!id.equals(group.id())||!group.resourceId().equals(row.get("resource_id"))||!group.fingerprint().equals(row.get("revision_fingerprint"))
            ||!group.hazard().equals(row.get("hazard_type"))||!group.manifestHash().equals(row.get("manifest_hash")))throw new IllegalStateException("STORED_MANIFEST_MISMATCH");
        var chunks=sql.queryForList("select * from rag_vector_chunk where group_id=? and model_key=? order by paragraph_index",id,model.key());
        if(chunks.size()!=group.paragraphs().size())throw new IllegalStateException("INCOMPLETE_CONTEXT_GROUP");
        for(int i=0;i<chunks.size();i++){
            var c=chunks.get(i);var p=group.paragraphs().get(i);
            if(!p.id().equals(c.get("chunk_id"))||p.ordinal()!=((Number)c.get("paragraph_index")).intValue()
                ||!p.text().equals(c.get("paragraph_text"))||!p.hash().equals(c.get("paragraph_hash"))||!p.locator().equals(c.get("source_locator"))
                ||!group.id().equals(c.get("mandatory_context_group"))||!group.resourceId().equals(c.get("resource_id"))
                ||!group.fingerprint().equals(c.get("revision_fingerprint"))||!group.extractionVersion().equals(c.get("extraction_version"))
                ||!JSON.writeValueAsString(group.sectionPath()).equals(c.get("section_path"))||!backend.contains(p.id(),model))throw new IllegalStateException("INCOMPLETE_CONTEXT_GROUP");
        }
        return Optional.of(group);
    }
    public List<RagChunkGroup> eligible(String hazard,EmbeddingPort.Model model,Instant now){
        var rows=sql.queryForList("select distinct g.group_id "+ELIGIBLE+" order by g.group_id limit 60",filters(hazard,model,now));
        return rows.stream().map(r->group((UUID)r.get("group_id"),model).orElseThrow()).toList();
    }
    public List<Hit> search(String hazard,EmbeddingPort.Model model,double[] query,Instant now,int limit){
        if(!available())throw new IllegalStateException("VECTOR_BACKEND_UNAVAILABLE");if(limit<1||limit>60)throw new IllegalArgumentException("VECTOR_RESULT_LIMIT");
        return backend.search(hazard,model,EmbeddingPort.vector(model,query),now,limit);
    }
}
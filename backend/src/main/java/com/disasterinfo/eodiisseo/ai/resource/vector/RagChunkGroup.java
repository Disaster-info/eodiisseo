package com.disasterinfo.eodiisseo.ai.resource.vector;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.adapter.KoreanLexicalParagraphSearch;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
/** Explicit reviewed manifest; no locator, heading, hazard or paragraph is inferred from a PDF/image. */
public record RagChunkGroup(UUID id,UUID resourceId,String fingerprint,String hazard,List<String> sectionPath,
        String extractionVersion,List<Paragraph> paragraphs) {
    public record Paragraph(UUID id,int ordinal,String text,String hash,String locator,boolean contextOnly) {
        public Paragraph {
            if(id==null||ordinal<0||text==null||text.isBlank()||text.length()>4000||!EmbeddingPort.sha(text).equals(hash)
                ||locator==null||locator.isBlank()||locator.length()>500)throw new IllegalArgumentException("Exact paragraph and explicit source locator required");
        }
        @Override public String toString(){return "RagParagraph[text=<redacted>, locator=<redacted>]";}
    }
    public static final Pattern BOUNDARY=Pattern.compile("(?>\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029])[\\p{Zs}\\t]*(?>\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029])(?:(?:\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029])[\\p{Zs}\\t]*)*");
    public RagChunkGroup {
        sectionPath=List.copyOf(sectionPath);paragraphs=List.copyOf(paragraphs);
        if(id==null||resourceId==null||fingerprint==null||!fingerprint.matches("[a-f0-9]{64}")||!Set.of("EARTHQUAKE","HEAVY_RAIN","WILDFIRE").contains(hazard)
            ||sectionPath.isEmpty()||sectionPath.size()>8||sectionPath.stream().anyMatch(v->v==null||v.isBlank()||v.length()>200)
            ||extractionVersion==null||extractionVersion.isBlank()||extractionVersion.length()>100||paragraphs.isEmpty()||paragraphs.size()>3
            ||paragraphs.stream().allMatch(Paragraph::contextOnly))throw new IllegalArgumentException("Bounded mandatory context manifest required");
        int prior=-1;var ids=new HashSet<UUID>();for(var p:paragraphs){if(p.ordinal()<=prior||!ids.add(p.id()))throw new IllegalArgumentException("Ordered unique paragraphs required");prior=p.ordinal();}
    }
    public List<String> texts(){return paragraphs.stream().map(Paragraph::text).toList();}
    public String manifestHash(){return EmbeddingPort.sha(JsonMapper.builder().build().writeValueAsString(this));}
    public void verify(OfficialResourceDocument doc){
        if(doc==null||!resourceId.equals(doc.getResourceId())||!fingerprint.equals(doc.revisionFingerprint())||doc.getContentText()==null
            ||doc.getContentText().length()>200000||KoreanLexicalParagraphSearch.suspicious(doc.getContentText()))throw new IllegalArgumentException("SOURCE_REVISION_MISMATCH");
        String[] original=BOUNDARY.split(doc.getContentText());for(var p:paragraphs)
            if(p.ordinal()>=original.length||!p.text().equals(original[p.ordinal()]))throw new IllegalArgumentException("SOURCE_PARAGRAPH_MISMATCH");
    }
    @Override public String toString(){return "RagChunkGroup[resource="+resourceId+", paragraphs="+paragraphs.size()+"]";}
    /** Explicit review of hazard, locators and mandatory grouping, separate from publication rights approval. */
    public interface PlanReview { boolean approved(UUID group,String fingerprint,String manifestHash,java.time.Instant now); }
}
package com.disasterinfo.eodiisseo.ai.resource.vector;

import com.disasterinfo.eodiisseo.ai.resource.adapter.KoreanLexicalParagraphSearch;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.port.ResourceParagraphSearch.Query;
import java.text.Normalizer;
import java.util.*;

/** Static-guidance safety scope only. It does not score or require query words in evidence. */
public final class SemanticGuidanceScope {
    private static final Map<String,List<String>> HAZARDS=Map.of(
        "EARTHQUAKE",List.of("지진","흔들","진동"),
        "HEAVY_RAIN",List.of("호우","폭우","침수","홍수"),
        "WILDFIRE",List.of("산불","산림화재","산에불","산에서불"));
    private static final List<String> DYNAMIC=List.of("실시간","지금","현재","오늘","우리동네","우리집","내집","내위치","근처","여기","이곳","위험한가","위험이있","위험여부","안전한지","안전한가","안전할까","안전여부","발생했","발생여부","피해액","발생시각","예상피해","경로","좌표","확률");
    private static final List<String> GUIDANCE=List.of("해야","행동","대처","대응","어떻게","대피","피하","요령","방법","이동해도","진입해도","접근해도","들어가도","이용해도","예방");
    public Optional<Query> prepare(String question){
        if(question==null||question.isBlank()||question.length()>10000||KoreanLexicalParagraphSearch.suspicious(question))return Optional.empty();
        String text=normalize(question.replaceAll("발생했을\\s*때","발생 시"));
        if(DYNAMIC.stream().anyMatch(text::contains)||GUIDANCE.stream().noneMatch(text::contains))return Optional.empty();
        var hazards=hazards(text);if(hazards.size()!=1)return Optional.empty();
        String hazard=hazards.iterator().next();return Optional.of(new Query(hazard,HAZARDS.get(hazard),List.of(),List.of()));
    }
    public boolean accepts(RagChunkGroup group,OfficialResourceDocument document){
        if(document==null||KoreanLexicalParagraphSearch.suspicious(document.getContentText()))return false;
        // Human-reviewed hazard/context manifest is required separately; mixed-hazard evidence stays blocked.
        var title=hazards(normalize(document.getTitle()));
        if(title.size()!=1||!title.contains(group.hazard()))return false;
        var body=hazards(normalize(String.join("\n",group.texts())));
        return body.stream().allMatch(group.hazard()::equals);
    }
    private static Set<String> hazards(String text){
        var found=new HashSet<String>();HAZARDS.forEach((key,words)->{if(words.stream().anyMatch(text::contains))found.add(key);});return found;
    }
    private static String normalize(String text){return text==null?"":Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+","");}
}
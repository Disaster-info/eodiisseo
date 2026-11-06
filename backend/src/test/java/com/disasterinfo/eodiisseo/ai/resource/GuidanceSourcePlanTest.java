package com.disasterinfo.eodiisseo.ai.resource;

import com.disasterinfo.eodiisseo.ai.resource.adapter.GuidanceSourcePlan;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real source metadata only. No official guidance body, rights grant or resource approval is created. */
class GuidanceSourcePlanTest {
    static List<Map<String,Object>> catalog() throws Exception {
        var root=JsonMapper.builder().build().readValue(Files.readString(Path.of(
            "src/main/java/com/disasterinfo/eodiisseo/ai/resource/PHASE6_SOURCE_CATALOG.json")),Map.class);
        return (List<Map<String,Object>>)root.get("sources");
    }
    @Test void allThreeOfficialCandidatesStayMetadataOnlyWithTraceablePlannedSections() throws Exception {
        var values=catalog();
        assertThat(values).hasSize(3);
        assertThat(values.stream().map(v->v.get("disasterType"))).containsExactlyInAnyOrder("EARTHQUAKE","HEAVY_RAIN","WILDFIRE");
        for(var source:values){
            var inspected=GuidanceSourcePlan.inspect(source);
            assertThat(inspected).containsEntry("approvalState","PENDING").containsEntry("runtimeSearchEligible",false)
                .containsEntry("contentFingerprint",null).containsEntry("sourceUrl",source.get("sourceUrl"));
            assertThat(inspected).doesNotContainKeys("contentText","html","resourceId");
            assertThat(inspected.get("planFingerprint").toString()).matches("[a-f0-9]{64}");
        }
    }
    @Test void metadataFingerprintIsStableAcrossInputMapOrderButBindsSourceTitleAndSectionLocator() throws Exception {
        var source=catalog().getFirst();var reordered=new TreeMap<>(source);
        String original=GuidanceSourcePlan.inspect(source).get("planFingerprint").toString();
        assertThat(GuidanceSourcePlan.inspect(reordered).get("planFingerprint")).isEqualTo(original);
        var changed=new LinkedHashMap<>(source);changed.put("sourceTitle","SYNTHETIC changed metadata title");
        assertThat(GuidanceSourcePlan.inspect(changed).get("planFingerprint")).isNotEqualTo(original);
        changed=new LinkedHashMap<>(source);
        var sections=new ArrayList<>((List<Map<String,Object>>)source.get("sections"));
        var first=new LinkedHashMap<>(sections.getFirst());first.put("sourceLocator","SYNTHETIC changed locator");
        sections.set(0,first);changed.put("sections",sections);
        assertThat(GuidanceSourcePlan.inspect(changed).get("planFingerprint")).isNotEqualTo(original);
    }
    @Test void sourcePlanCannotGrantRightsOrPretendAnUninspectedBodyHasAFingerprint() throws Exception {
        for(var override:List.of(Map.<String,Object>of("approvalState","APPROVED"),
            Map.<String,Object>of("rightsState","APPROVED"),Map.<String,Object>of("contentFingerprint","fake-content-fingerprint"),
            Map.<String,Object>of("sourceUrl","https://www.mois.go.kr.evil.invalid/test"))){
            var source=new LinkedHashMap<>(catalog().getFirst());source.putAll(override);
            assertThatThrownBy(()->GuidanceSourcePlan.inspect(source)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void scopedRightsAndValidContentHashRemainPendingAndBindPublisher() throws Exception {
        var source=new LinkedHashMap<>(catalog().getFirst());
        source.put("sourceUrl","https://www.rda.go.kr/board/synthetic");source.put("publisher","농촌진흥청");
        source.put("rightsState","TYPE1_SCOPED_VERIFIED");source.put("rightsEvidence","SYNTHETIC scoped evidence only");
        source.put("contentFingerprint","a".repeat(64));
        var result=GuidanceSourcePlan.inspect(source);
        assertThat(result).containsEntry("approvalState","PENDING").containsEntry("runtimeSearchEligible",false).containsEntry("contentFingerprint","a".repeat(64));
        source.put("publisher","행정안전부");
        assertThatThrownBy(()->GuidanceSourcePlan.inspect(source)).isInstanceOf(IllegalArgumentException.class);
    }
}

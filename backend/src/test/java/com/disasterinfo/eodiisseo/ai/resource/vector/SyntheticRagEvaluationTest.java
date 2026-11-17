package com.disasterinfo.eodiisseo.ai.resource.vector;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SyntheticRagEvaluationTest {
    ChatOfficialResourceSearchPort.OfficialResource evidence(UUID id,String body){return new ChatOfficialResourceSearchPort.OfficialResource(id,"SYNTHETIC","SYNTHETIC",URI.create("https://example.invalid/synthetic"),body,true,true,true);}
    @Test void sharedDatasetIsSyntheticAndContainsParaphrasesAndBlockedQuestions(){
        assertThat(SyntheticRagEvaluation.documents()).hasSize(9);assertThat(SyntheticRagEvaluation.questions()).hasSize(23);
        assertThat(SyntheticRagEvaluation.documents()).allSatisfy(d->{assertThat(d.title()).startsWith("SYNTHETIC");assertThat(d.paragraphs()).allMatch(p->p.startsWith("합성 검증용"));});
        assertThat(SyntheticRagEvaluation.questions().stream().filter(q->!q.unsupported()).map(SyntheticRagEvaluation.Question::hazard).distinct()).containsExactlyInAnyOrder("EARTHQUAKE","HEAVY_RAIN","WILDFIRE");
        assertThat(SyntheticRagEvaluation.questions()).anyMatch(q->q.text().equals("호우가 내릴 때 지하차도에 접근해도 되나요?"));
    }
    @Test void rankContextUnsupportedAndFailureMetricsAreCalculatedRatherThanInvented(){
        var docs=SyntheticRagEvaluation.documents();var d=docs.getFirst();var other=docs.get(3);
        var good=new SyntheticRagEvaluation.Question("GOOD",d.hazard(),"GOOD",d.id(),d.paragraphs());
        var missing=new SyntheticRagEvaluation.Question("MISSING",d.hazard(),"MISSING",d.id(),d.paragraphs());
        var blocked=new SyntheticRagEvaluation.Question("BLOCKED",null,"BLOCKED",null,List.of());
        ChatOfficialResourceSearchPort strategy=q->new ChatOfficialResourceSearchPort.SearchResult(q.equals("GOOD")?List.of(evidence(other.id(),other.paragraphs().getFirst()),evidence(d.id(),String.join("\n\n",d.paragraphs()))):List.of(evidence(d.id(),d.paragraphs().getFirst())),true);
        var m=SyntheticRagEvaluation.evaluate(strategy,List.of(good,missing,blocked),docs);
        assertThat(m.recallAt3()).isEqualTo(.5);assertThat(m.mrr()).isEqualTo(.25);assertThat(m.paragraphHitRate()).isEqualTo(1);assertThat(m.missingContextRate()).isEqualTo(.5);assertThat(m.unsupportedResponseRate()).isEqualTo(1);assertThat(m.wrongHazardRate()).isEqualTo(1.0/3);assertThat(m.p95Millis()).isGreaterThanOrEqualTo(m.p50Millis());
        var missingPrimary=SyntheticRagEvaluation.evaluate(q->new ChatOfficialResourceSearchPort.SearchResult(List.of(evidence(d.id(),d.paragraphs().get(1))),true),List.of(good),docs);
        assertThat(missingPrimary.missingContextRate()).isEqualTo(1);assertThat(missingPrimary.paragraphHitRate()).isZero();
        var failed=SyntheticRagEvaluation.evaluate(q->{throw new IllegalStateException("SYNTHETIC");},List.of(good,blocked),docs);
        assertThat(failed.failures()).isEqualTo(2);assertThat(failed.recallAt3()).isZero();assertThat(failed.fallbackRate()).isEqualTo(1);
    }
    @Test void optionalStrategyComparisonUsesOneIdenticalDatasetWithoutModelCalls(){
        ChatOfficialResourceSearchPort empty=q->new ChatOfficialResourceSearchPort.SearchResult(List.of(),false);
        var comparison=SyntheticRagEvaluation.compare(Map.of("Lexical",empty,"VectorExact",empty));
        assertThat(comparison).hasSize(2);assertThat(comparison.get("Lexical").questions()).isEqualTo(comparison.get("VectorExact").questions());assertThat(comparison.get("Lexical").questions()).isEqualTo(23);
    }
}
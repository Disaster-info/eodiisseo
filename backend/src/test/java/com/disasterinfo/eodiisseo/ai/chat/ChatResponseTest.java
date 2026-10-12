package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse.AnswerType;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse.Source;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ChatResponseTest {

    @Autowired
    private JsonMapper mapper;

    @Test
    void api521SuccessKeysDoNotExposeMemberQuestionModelOrInternalResourceIds() {
        ChatResponse response = new ChatResponse(AnswerType.AI, "공식 근거에 따른 대응 안내",
                List.of(new Source("공식기관", "공식 행동요령")), Instant.parse("2026-10-12T01:00:00Z"));
        assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(mapper.readTree("""
                {
                  "answerType":"AI",
                  "answer":"공식 근거에 따른 대응 안내",
                  "sources":[{"organization":"공식기관","title":"공식 행동요령"}],
                  "generatedAt":"2026-10-12T01:00:00Z"
                }
                """));
    }

    @Test
    void fallbackSerializesNoMadeUpSources() {
        ChatResponse response = new ChatResponse(AnswerType.FALLBACK, "공식기관에서 제공하는 재난 행동요령을 확인하세요.",
                List.of(), Instant.parse("2026-10-12T01:00:00Z"));
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(json.get("answerType").asString()).isEqualTo("FALLBACK");
        assertThat(json.get("sources").isArray()).isTrue();
        assertThat(json.get("sources").size()).isZero();
        assertThat(json.size()).isEqualTo(4);
    }

    @Test
    void responseDefensivelyCopiesSourceCollectionAndRedactsAnswerFromDiagnostics() {
        List<Source> sources = new ArrayList<>();
        sources.add(new Source("공식기관", "제목"));
        ChatResponse response = new ChatResponse(AnswerType.AI, "개인 상황을 포함할 수 있는 안내", sources, Instant.EPOCH);
        sources.clear();
        assertThat(response.sources()).hasSize(1);
        assertThat(response.toString()).doesNotContain(response.answer());
    }
}

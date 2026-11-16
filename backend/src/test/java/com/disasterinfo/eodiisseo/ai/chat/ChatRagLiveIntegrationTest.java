package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.ai.chat.adapter.OllamaChatAnswerGenerator;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatRequest;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse;
import com.disasterinfo.eodiisseo.ai.chat.port.*;
import com.disasterinfo.eodiisseo.ai.ollama.OllamaChatClient;
import com.disasterinfo.eodiisseo.ai.ollama.OllamaSettings;
import com.disasterinfo.eodiisseo.ai.resource.ResourceService;
import com.disasterinfo.eodiisseo.ai.resource.adapter.KoreanLexicalParagraphSearch;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.port.OfficialResourceStore;
import com.disasterinfo.eodiisseo.ai.resource.port.ResourceApprovalPort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real local LLM + actual lexical retrieval. Public text/approval/auth/storage remain synthetic test fixtures. */
@Tag("ollama-live")
@EnabledIfEnvironmentVariable(named = "EODIISSEO_AI_OLLAMA_LIVE_TEST", matches = "true")
class ChatRagLiveIntegrationTest {
    @Test @Timeout(180)
    void retrievedKoreanFixtureParagraphFlowsThroughTheExistingRealModelAndCitationGuard() {
        Instant now = Instant.now();
        String paragraph = "테스트용 가상 안내입니다. 지진 때는 탁자 아래에서 몸을 보호하세요.";
        UUID id = UUID.randomUUID();
        var document = new OfficialResourceDocument(id, "행정안전부", "지진 테스트 Fixture",
                "https://www.safekorea.go.kr/test-fixture-not-a-published-source", "TEST-ONLY", paragraph, now);
        var resources = mock(OfficialResourceStore.class);
        when(resources.isAvailable()).thenReturn(true);
        when(resources.searchCandidates(anyList(), anyInt())).thenReturn(List.of(document));
        ResourceApprovalPort approvals = ignored -> Optional.of(new ResourceApprovalPort.Approval(
                id, document.revisionFingerprint(), now, now.plus(Duration.ofMinutes(15)), true));
        var search = new ResourceService(resources, new KoreanLexicalParagraphSearch(), Optional.of(approvals));
        var authentication = mock(ChatAuthenticationPort.class);
        doAnswer(call -> { call.<Runnable>getArgument(2).run(); return null; })
                .when(authentication).runWithActiveSession(any(), any(), any());
        var store = mock(ChatAnswerStore.class);
        when(store.isAvailable()).thenReturn(true);
        var retention = mock(ChatRetentionPolicy.class);
        when(retention.isApproved()).thenReturn(true);
        when(retention.approvedExpiresAt(any(), any())).thenAnswer(call -> call.<Instant>getArgument(1).plus(Duration.ofDays(90)));
        ChatPromptPrivacyPort privacy = question -> Optional.of(new ChatPromptPrivacyPort.PreparedQuestion(question, true));
        try (var client = new OllamaChatClient(OllamaSettings.from(new StandardEnvironment()))) {
            var service = new ChatService(Optional.of(authentication), Optional.of(search),
                    Optional.of(new OllamaChatAnswerGenerator(client)), Optional.of(store), Optional.of(retention), Optional.of(privacy));
            var response = service.requestGuidance(UUID.randomUUID(), UUID.randomUUID(), new ChatRequest("지진 때 어떻게 행동해야 하나요?"));
            assertThat(response.answerType()).isEqualTo(ChatResponse.AnswerType.AI);
            assertThat(response.answer()).isEqualTo(paragraph);
            assertThat(response.sources()).containsExactly(new ChatResponse.Source("행정안전부", "지진 테스트 Fixture"));
            verify(store).save(any(), eq(List.of(id)));
            System.out.println("OLLAMA_RAG_LIVE model=" + client.modelName() + " outcome=COMPLETED fixture=true");
        }
    }
}

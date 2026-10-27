package com.disasterinfo.eodiisseo.ai.ollama;

import com.disasterinfo.eodiisseo.ai.chat.ChatService;
import com.disasterinfo.eodiisseo.ai.chat.adapter.OllamaChatAnswerGenerator;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatRequest;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer;
import com.disasterinfo.eodiisseo.ai.chat.port.*;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort.GeneratedAnswer;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort.OfficialResource;
import com.disasterinfo.eodiisseo.ai.summary.*;
import com.disasterinfo.eodiisseo.ai.summary.adapter.OllamaSummaryGenerator;
import com.disasterinfo.eodiisseo.ai.summary.port.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Explicit opt-in real model calls. Authentication, search, queue and storage are test Fakes,
 * not production implementations. No fixture is inserted into any database.
 */
@Tag("ollama-live")
@EnabledIfEnvironmentVariable(named = "EODIISSEO_AI_OLLAMA_LIVE_TEST", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OllamaLiveIntegrationTest {
    private static final String GUIDE = "테스트용 가상 안내입니다. 침수된 도로에는 들어가지 마세요.";
    private OllamaChatClient client;

    @BeforeEach void openClient() {
        client = new OllamaChatClient(OllamaSettings.from(new StandardEnvironment())) {
            @Override public Response chat(String system, String user, java.util.Map<String,Object> schema, Instant deadline) {
                Response response = super.chat(system,user,schema,deadline);
                // Structural diagnostics for synthetic public fixtures only; never print their text or IDs.
                var output = OllamaJson.readObject(response.content());
                if (output.has("answer")) {
                    var citations = output.path("citations");
                    boolean exactCitation = citations.isArray() && citations.size() == 1
                            && GUIDE.equals(citations.get(0).path("excerpt").asString());
                    System.out.println("OLLAMA_LIVE_SHAPE answer_exact=" + GUIDE.equals(output.path("answer").asString())
                            + " citation_exact=" + exactCitation + " citations=" + citations.size()
                            + " used_ids=" + output.path("usedResourceIds").size()
                            + " sufficient=" + output.path("sufficientEvidence").asBoolean());
                }
                return response;
            }
        };
    }
    @AfterEach void closeClient() { if (client != null) client.close(); }

    @Test @Order(1) @Timeout(180)
    void realKoreanSummaryPassesExistingRegistrationWorkerAndReadService() {
        Instant created = Instant.now();
        var revision = new OfficialRevision(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "TEST_HEAVY_RAIN", "테스트 전용 가상 지역", OfficialRevision.DisasterStatus.ONGOING,
                "TEST_WARNING", "이 문서는 실제 재난이 아닌 통합 테스트용 가상 자료입니다.\n\n"
                + "가상 지역에 테스트용 호우 상황이 진행 중입니다.\n\n"
                + "행동요령은 실내에서 공식 안내를 확인하는 것입니다.", created, OfficialRevision.Origin.OFFICIAL);
        SummarySourceReader sources = mock(SummarySourceReader.class);
        when(sources.findRevision(revision.revisionId())).thenReturn(Optional.of(revision));
        when(sources.isCurrentRevision(revision.revisionId())).thenReturn(true);
        when(sources.findDisaster(revision.disasterId())).thenReturn(Optional.of(new SummarySourceReader.Source(
                revision.disasterId(), revision.origin(), Optional.of(revision))));
        SummaryStore store = mock(SummaryStore.class);
        AtomicReference<SummaryState> stored = new AtomicReference<>();
        UUID summaryId = UUID.randomUUID();
        when(store.register(eq(revision), any())).thenAnswer(call -> {
            var state = new SummaryState(summaryId, revision.disasterId(), revision.revisionId(), SummaryStatus.PENDING,
                    null, null, 0, call.getArgument(1), null);
            stored.set(state); return state;
        });
        when(store.findByRevision(revision.revisionId())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(store.attempt(eq(revision.revisionId()), anyInt(), any(), any(), any())).thenAnswer(call -> {
            Supplier<SummaryStore.Outcome> worker = call.getArgument(4);
            var outcome = worker.get();
            SummaryStatus status = SummaryStatus.valueOf(outcome.result().name());
            var state = new SummaryState(summaryId, revision.disasterId(), revision.revisionId(), status,
                    outcome.summary(), outcome.modelName(), 0, created, outcome.completedAt());
            stored.set(state); return new SummaryStore.Attempt(outcome.result(), state);
        });
        // Policy values here are fixture values, not a production retry default.
        var service = new SummaryService(Optional.of(mock(SummaryAuthPort.class)), Optional.of(sources), Optional.of(store),
                Optional.of(new OllamaSummaryGenerator(client)), Optional.of(mock(SummaryTaskDispatcher.class)),
                Optional.of(() -> Optional.of(new SummaryRetryPolicy.Policy(0, Duration.ZERO))), Optional.empty());
        assertThat(service.onOfficialRevisionSaved(revision.revisionId(), created)).isEqualTo(SummaryService.Submission.QUEUED);
        long started = System.nanoTime();
        assertThat(service.processRevision(revision.revisionId(), Instant.now())).isEqualTo(SummaryService.Processing.COMPLETED);
        var response = service.getSummary(UUID.randomUUID(), UUID.randomUUID(), revision.disasterId());
        assertThat(response.status()).isEqualTo(SummaryStatus.COMPLETED);
        assertThat(response.summary()).contains("테스트 전용 가상 지역", "TEST_WARNING");
        assertThat(response.generatedAt()).isAfterOrEqualTo(created);
        assertThat(stored.get().modelName()).isEqualTo(client.modelName());
        metric("summary", started, "COMPLETED");
    }

    @Test @Order(2) @Timeout(180)
    void realGroundedKoreanAnswerUsesOnlyTheProvidedFixtureAndFlowsThroughChatService() {
        var generated = new AtomicReference<GeneratedAnswer>();
        var saved = new AtomicReference<ChatAnswer>();
        ChatService service = chatService(generated, saved);
        long started = System.nanoTime();
        var response = service.requestGuidance(UUID.randomUUID(), UUID.randomUUID(),
                new ChatRequest("이 가상 안내자료에 따르면 침수된 도로에서는 어떻게 행동해야 하나요?"));
        assertThat(response.answerType()).isEqualTo(ChatResponse.AnswerType.AI);
        assertThat(response.answer()).isEqualTo(GUIDE);
        assertThat(generated.get().grounded()).isTrue();
        assertThat(generated.get().modelName()).isEqualTo(client.modelName());
        assertThat(response.sources()).hasSize(1);
        assertThat(saved.get().getStatus()).isEqualTo(ChatAnswer.Status.COMPLETED);
        metric("chat-grounded", started, "COMPLETED");
    }

    @Test @Order(3) @Timeout(180)
    void realModelRejectsAnUnsupportedQuestionAndExistingChatFallbackPersistsNoInventedAnswer() {
        var generated = new AtomicReference<GeneratedAnswer>();
        var saved = new AtomicReference<ChatAnswer>();
        ChatService service = chatService(generated, saved);
        long started = System.nanoTime();
        var response = service.requestGuidance(UUID.randomUUID(), UUID.randomUUID(),
                new ChatRequest("이 테스트 자료로 지진 발생 시각과 예상 피해액을 알 수 있나요?"));
        assertThat(response.answerType()).isEqualTo(ChatResponse.AnswerType.FALLBACK);
        assertThat(generated.get()).isNotNull();
        assertThat(generated.get().grounded()).isFalse();
        assertThat(generated.get().usedResourceIds()).isEmpty();
        assertThat(response.sources()).isEmpty();
        assertThat(saved.get().getStatus()).isNotEqualTo(ChatAnswer.Status.COMPLETED);
        metric("chat-insufficient", started, "FALLBACK");
    }

    private ChatService chatService(AtomicReference<GeneratedAnswer> generated, AtomicReference<ChatAnswer> saved) {
        var source = new OfficialResource(UUID.randomUUID(), "통합 테스트 가상 기관", "테스트 Fixture 안내",
                URI.create("https://fixture.invalid/guide"), GUIDE, true, true, true);
        var adapter = new OllamaChatAnswerGenerator(client);
        ChatAnswerGenerationPort recordedGeneration = new ChatAnswerGenerationPort() {
            @Override public boolean isConfigured() { return adapter.isConfigured(); }
            @Override public GeneratedAnswer generate(GenerationRequest request) {
                var answer = adapter.generate(request); generated.set(answer); return answer;
            }
        };
        ChatAuthenticationPort authentication = mock(ChatAuthenticationPort.class);
        doAnswer(call -> { call.<Runnable>getArgument(2).run(); return null; })
                .when(authentication).runWithActiveSession(any(), any(), any());
        ChatAnswerStore store = mock(ChatAnswerStore.class);
        when(store.isAvailable()).thenReturn(true);
        doAnswer(call -> { saved.set(call.getArgument(0)); return null; }).when(store).save(any(), any());
        ChatRetentionPolicy retention = mock(ChatRetentionPolicy.class);
        when(retention.isApproved()).thenReturn(true);
        // This test-only expiry fixture does not resolve the production retention-policy conflict.
        when(retention.approvedExpiresAt(any(), any())).thenAnswer(call -> call.<Instant>getArgument(1).plus(Duration.ofDays(90)));
        return new ChatService(Optional.of(authentication),
                Optional.of(question -> new ChatOfficialResourceSearchPort.SearchResult(List.of(source), true)),
                Optional.of(recordedGeneration), Optional.of(store), Optional.of(retention),
                Optional.of(question -> Optional.of(new ChatPromptPrivacyPort.PreparedQuestion(question, true))));
    }

    private void metric(String caseName, long startNanos, String outcome) {
        // No question, source, output text, token or location is written to logs.
        System.out.println("OLLAMA_LIVE case=" + caseName + " model=" + client.modelName()
                + " elapsed_ms=" + Duration.ofNanos(System.nanoTime() - startNanos).toMillis() + " outcome=" + outcome);
    }
}

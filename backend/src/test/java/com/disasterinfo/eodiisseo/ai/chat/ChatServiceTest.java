package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.ai.chat.dto.ChatRequest;
import com.disasterinfo.eodiisseo.ai.chat.adapter.OllamaChatAnswerGenerator;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse.AnswerType;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer.Status;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort.GeneratedAnswer;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort.GenerationRequest;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerStore;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort.BasicGuidance;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort.OfficialResource;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort.SearchResult;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatPromptPrivacyPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatPromptPrivacyPort.PreparedQuestion;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatRetentionPolicy;
import com.disasterinfo.eodiisseo.ai.ollama.OllamaChatClient;
import com.disasterinfo.eodiisseo.ai.ollama.OllamaJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private static final UUID MEMBER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID RESOURCE_ONE = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID RESOURCE_TWO = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final Instant START = Instant.parse("2026-10-12T01:00:00Z");
    private static final String QUESTION = "지진 발생 시 어떻게 대응해야 하나요?";

    private final MutableClock clock = new MutableClock(START);
    private final FakeAuthentication auth = new FakeAuthentication();
    private final FakeStore store = new FakeStore();
    private final AtomicInteger searchCalls = new AtomicInteger();
    private final FakeGenerator generator = new FakeGenerator();
    private final FakePrivacy privacy = new FakePrivacy();
    private String searchedQuestion;
    private SearchResult searchResult = new SearchResult(List.of(resource(RESOURCE_ONE), resource(RESOURCE_TWO)), true);
    private Function<String, SearchResult> searchAction = question -> searchResult;

    @Test
    void storesOnlyActuallyUsedOfficialResourcesAndPreservesTheOfficialEvidence() {
        generator.result = new GeneratedAnswer("제공된 공식 행동요령에 따른 안내", List.of(RESOURCE_TWO, RESOURCE_TWO), true, "test-model");
        ChatResponse response = service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));

        assertThat(response.answerType()).isEqualTo(AnswerType.AI);
        assertThat(response.sources()).containsExactly(new ChatResponse.Source("공식기관", "공식 행동요령"));
        assertThat(response.generatedAt()).isEqualTo(START);
        assertThat(generator.request.question()).isEqualTo(QUESTION);
        assertThat(generator.request.evidence()).containsExactly(resource(RESOURCE_ONE), resource(RESOURCE_TWO));
        assertThat(generator.request.deadline()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        assertThat(generator.request.groundingInstruction()).contains("official severity", "resource IDs actually used", "safety guarantees");
        ChatAnswer saved = store.onlyAnswer();
        assertThat(saved.getQuestionText()).isEqualTo(QUESTION);
        assertThat(saved.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(saved.getModelName()).isEqualTo("test-model");
        assertThat(saved.getCreatedAt()).isEqualTo(START);
        assertThat(saved.getExpiresAt()).isEqualTo(START.plus(Duration.ofDays(90)));
        assertThat(store.resources.get(saved.getAnswerId())).containsExactly(RESOURCE_TWO);
        assertThat(auth.checks).isEqualTo(2);
    }

    @Test
    void insufficientSearchDoesNotAskAnAiToGuess() {
        searchResult = new SearchResult(List.of(resource(RESOURCE_ONE)), false);
        assertFallback(Status.INSUFFICIENT);
        assertThat(generator.calls).isZero();
    }

    @Test
    void emptyOfficialResultProducesAnExplicitFallbackWithoutFictitiousSources() {
        searchResult = new SearchResult(List.of(), true);
        assertFallback(Status.INSUFFICIENT);
        assertThat(generator.calls).isZero();
    }

    @ParameterizedTest
    @MethodSource("unusableEvidence")
    void incompleteUnofficialOrUnrelatedEvidenceCannotBecomeAnAiAnswer(OfficialResource resource) {
        searchResult = new SearchResult(List.of(resource), true);
        assertFallback(Status.INSUFFICIENT);
        assertThat(generator.calls).isZero();
    }

    static Stream<OfficialResource> unusableEvidence() {
        return Stream.of(
                new OfficialResource(null, "공식기관", "제목", URI.create("https://agency.example/guide"), "내용", true, true, true),
                new OfficialResource(RESOURCE_ONE, null, "제목", URI.create("https://agency.example/guide"), "내용", true, true, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", " ", URI.create("https://agency.example/guide"), "내용", true, true, true),
                new OfficialResource(RESOURCE_ONE, "\u00a0", "제목", URI.create("https://agency.example/guide"), "내용", true, true, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", "\u00a0", URI.create("https://agency.example/guide"), "내용", true, true, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", "제목", URI.create("https://agency.example/guide"), "\u00a0", true, true, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", "제목", null, "내용", true, true, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", "제목", URI.create("javascript:alert(1)"), "내용", true, true, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", "제목", URI.create("https://secret@agency.example/guide"), "내용", true, true, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", "제목", URI.create("https://agency.example/guide"), null, true, true, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", "제목", URI.create("https://agency.example/guide"), "내용", false, true, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", "제목", URI.create("https://agency.example/guide"), "내용", true, false, true),
                new OfficialResource(RESOURCE_ONE, "공식기관", "제목", URI.create("https://agency.example/guide"), "개인정보가 있는 자료", true, true, false));
    }

    @Test
    void conflictingVersionsUnderTheSameResourceIdAreNotSilentlyCombined() {
        OfficialResource changed = new OfficialResource(RESOURCE_ONE, "공식기관", "공식 행동요령",
                URI.create("https://agency.example/guide"), "서로 다른 원문", true, true, true);
        searchResult = new SearchResult(List.of(resource(RESOURCE_ONE), changed), true);
        assertFallback(Status.INSUFFICIENT);
        assertThat(generator.calls).isZero();
    }

    @ParameterizedTest
    @MethodSource("untrustedAnswers")
    void ungroundedAnswersAndInventedCitationsAreDiscarded(GeneratedAnswer answer) {
        generator.result = answer;
        assertFallback(Status.FAILED);
        assertThat(store.onlyAnswer().getModelName()).isNull();
    }

    static Stream<Arguments> untrustedAnswers() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new GeneratedAnswer("", List.of(RESOURCE_ONE), true, null)),
                Arguments.of(new GeneratedAnswer("\u00a0", List.of(RESOURCE_ONE), true, null)),
                Arguments.of(new GeneratedAnswer("근거 없는 안내", List.of(RESOURCE_ONE), false, null)),
                Arguments.of(new GeneratedAnswer("출처 없는 안내", null, true, null)),
                Arguments.of(new GeneratedAnswer("출처 없는 안내", List.of(), true, null)),
                Arguments.of(new GeneratedAnswer("미사용 출처 연결", List.of(UUID.randomUUID()), true, null)),
                Arguments.of(new GeneratedAnswer("모델정보 오류", List.of(RESOURCE_ONE), true, "x".repeat(101))));
    }

    @Test
    void unconfiguredLlmIsNeverCalledAndCannotClaimIntegrationSuccess() {
        generator.configured = false;
        assertFallback(Status.FALLBACK);
        assertThat(generator.calls).isZero();
    }

    @Test
    void configurationCheckCannotOutliveTheTotalDeadlineAndClaimAnOrdinaryFallback() {
        generator.configurationAction = () -> {
            clock.advance(Duration.ofMinutes(10));
            return false;
        };

        assertFallback(Status.TIMEOUT);
        assertThat(generator.calls).isZero();
    }

    @Test
    void missingLlmAdapterStillProvidesAndPersistsBasicGuidance() {
        ChatService service = new ChatService(Optional.of(auth), Optional.of(search()), Optional.empty(), Optional.of(store), Optional.of(requestTimePolicy()), Optional.of(privacy), clock);
        assertThat(service.requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)).answerType()).isEqualTo(AnswerType.FALLBACK);
        assertThat(store.onlyAnswer().getStatus()).isEqualTo(Status.FALLBACK);
    }

    @Test
    void missingSearchAdapterDoesNotPretendThereWereNoOfficialResources() {
        ChatService service = new ChatService(Optional.of(auth), Optional.empty(), Optional.of(generator), Optional.of(store), Optional.of(requestTimePolicy()), Optional.of(privacy), clock);
        assertThat(service.requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)).sources()).isEmpty();
        assertThat(store.onlyAnswer().getStatus()).isEqualTo(Status.FALLBACK);
        assertThat(generator.calls).isZero();
    }

    @Test
    void llmFailureProducesOnlyBasicGuidanceAndNeverLeaksProviderSecrets() {
        generator.failure = new IllegalStateException("api_key=SECRET latitude=37.123 question=" + QUESTION);
        ChatResponse response = assertFallback(Status.FAILED);
        assertThat(response.answer()).doesNotContain("SECRET", "37.123", QUESTION);
        assertThat(store.onlyAnswer().getAnswerText()).isEqualTo(response.answer());
    }

    @Test
    void searchFailureIsIsolatedAndDoesNotReachLlm() {
        searchAction = question -> { throw new IllegalStateException("SECRET"); };
        assertFallback(Status.FAILED);
        assertThat(generator.calls).isZero();
    }

    @Test
    void actualSelectedOfficialBasicGuidanceIsReturnedWhenTheLlmFails() {
        searchResult = new SearchResult(List.of(resource(RESOURCE_ONE), resource(RESOURCE_TWO)), true,
                new BasicGuidance(RESOURCE_TWO, "공식 위험등급은 변경하지 않는다."));
        generator.failure = new IllegalStateException("LLM unavailable");

        ChatResponse response = service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));

        assertThat(response.answerType()).isEqualTo(AnswerType.FALLBACK);
        assertThat(response.answer()).isEqualTo("공식 위험등급은 변경하지 않는다.");
        assertThat(response.sources()).containsExactly(new ChatResponse.Source("공식기관", "공식 행동요령"));
        assertThat(store.onlyAnswer().getStatus()).isEqualTo(Status.FAILED);
        assertThat(store.resources.get(store.onlyAnswer().getAnswerId())).containsExactly(RESOURCE_TWO);
    }

    @Test
    void selectedOfficialBasicGuidanceWorksWhenTheQuestionHasInsufficientEvidence() {
        searchResult = new SearchResult(List.of(resource(RESOURCE_TWO)), false,
                new BasicGuidance(RESOURCE_TWO, "공식 위험등급은 변경하지 않는다."));

        ChatResponse response = service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));

        assertThat(response.answer()).isEqualTo("공식 위험등급은 변경하지 않는다.");
        assertThat(store.onlyAnswer().getStatus()).isEqualTo(Status.INSUFFICIENT);
        assertThat(generator.calls).isZero();
        assertThat(store.resources.get(store.onlyAnswer().getAnswerId())).containsExactly(RESOURCE_TWO);
    }

    @ParameterizedTest
    @MethodSource("invalidBasicGuidance")
    void noInventedBasicGuidanceOrArbitraryFirstResourceSelectionIsUsed(BasicGuidance guidance) {
        searchResult = new SearchResult(List.of(resource(RESOURCE_ONE)), false, guidance);
        assertFallback(Status.INSUFFICIENT);
        assertThat(generator.calls).isZero();
    }

    static Stream<BasicGuidance> invalidBasicGuidance() {
        return Stream.of(new BasicGuidance(RESOURCE_TWO, "공식 위험등급은 변경하지 않는다."),
                new BasicGuidance(RESOURCE_ONE, "근거 원문에 없는 안전 보장"),
                new BasicGuidance(RESOURCE_ONE, " "), new BasicGuidance(RESOURCE_ONE, null));
    }

    @Test
    void timeoutReturnsAlreadySelectedOfficialBasicGuidanceWithoutLateAiText() {
        searchResult = new SearchResult(List.of(resource(RESOURCE_TWO)), true,
                new BasicGuidance(RESOURCE_TWO, "공식 위험등급은 변경하지 않는다."));
        generator.action = request -> {
            clock.advance(Duration.ofMinutes(10));
            return new GeneratedAnswer("late AI text", List.of(RESOURCE_TWO), true, null);
        };

        ChatResponse response = service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));
        assertThat(response.answer()).isEqualTo("공식 위험등급은 변경하지 않는다.");
        assertThat(store.onlyAnswer().getStatus()).isEqualTo(Status.TIMEOUT);
        assertThat(store.resources.get(store.onlyAnswer().getAnswerId())).containsExactly(RESOURCE_TWO);
    }

    @Test
    void answerAtTheTenMinuteDeadlineIsDiscardedAndRecordedAsTimeout() {
        generator.action = request -> {
            clock.advance(Duration.ofMinutes(10));
            return generator.result;
        };
        assertFallback(Status.TIMEOUT);
        assertThat(store.onlyAnswer().getCompletedAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        assertThat(store.onlyAnswer().getModelName()).isNull();
    }

    @Test
    void providerFailureAtTheDeadlineIsRecordedAsTimeoutInsteadOfAnEarlyFailure() {
        generator.action = request -> {
            clock.advance(Duration.ofMinutes(10));
            throw new IllegalStateException("provider failed after the request deadline");
        };

        assertFallback(Status.TIMEOUT);
        assertThat(store.onlyAnswer().getCompletedAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
    }

    @Test
    void searchAndGenerationShareOneTenMinuteBudget() {
        searchAction = question -> {
            clock.advance(Duration.ofMinutes(9));
            return searchResult;
        };
        generator.action = request -> {
            clock.advance(Duration.ofMinutes(2));
            return generator.result;
        };
        assertFallback(Status.TIMEOUT);
        assertThat(generator.request.deadline()).isEqualTo(START.plus(Duration.ofMinutes(10)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t", "\u2003", "\u00a0", "\u2007", "\u202f"})
    void blankQuestionIsRejectedBeforeSearchAndStorage(String question) {
        assertFailure(() -> service().requestGuidance(MEMBER, SESSION, new ChatRequest(question)), ChatException.Reason.INVALID_QUESTION);
        assertThat(searchCalls).hasValue(0);
        assertThat(store.answers).isEmpty();
    }

    @Test
    void nullRequestIsRejected() {
        assertFailure(() -> service().requestGuidance(MEMBER, SESSION, null), ChatException.Reason.INVALID_QUESTION);
    }

    @Test
    void noUndocumentedMaximumQuestionLengthIsIntroduced() {
        String question = "장문 질문 ".repeat(1000);
        service().requestGuidance(MEMBER, SESSION, new ChatRequest(question));
        assertThat(generator.request.question()).isEqualTo(question);
        assertThat(store.onlyAnswer().getQuestionText()).isEqualTo(question);
    }

    @Test
    void missingMemberOrServiceSessionCannotReachDependencies() {
        assertFailure(() -> service().requestGuidance(null, SESSION, new ChatRequest(QUESTION)), ChatException.Reason.UNAUTHENTICATED);
        assertFailure(() -> service().requestGuidance(MEMBER, null, new ChatRequest(QUESTION)), ChatException.Reason.UNAUTHENTICATED);
        assertThat(searchCalls).hasValue(0);
        assertThat(store.answers).isEmpty();
    }

    @Test
    void absentActualAuthenticationAdapterFailsClosed() {
        ChatService service = new ChatService(Optional.empty(), Optional.of(search()), Optional.of(generator), Optional.of(store), Optional.of(requestTimePolicy()), Optional.of(privacy), clock);
        assertFailure(() -> service.requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)), ChatException.Reason.DEPENDENCY_UNAVAILABLE);
        assertThat(searchCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(SessionFailure.class)
    void inactiveExpiredRevokedOrOtherMembersSessionIsRejected(SessionFailure failure) {
        switch (failure) {
            case INACTIVE -> auth.active = false;
            case EXPIRED -> auth.expired = true;
            case REVOKED -> auth.revoked = true;
            case OTHER_MEMBER -> auth.member = UUID.randomUUID();
        }
        assertFailure(() -> service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)), ChatException.Reason.UNAUTHENTICATED);
        assertThat(searchCalls).hasValue(0);
        assertThat(generator.calls).isZero();
        assertThat(store.answers).isEmpty();
    }

    @Test
    void withdrawalDuringGenerationCannotRecreateTheDeletedPrivateAnswer() {
        generator.action = request -> {
            auth.active = false;
            store.deleteByMemberId(MEMBER);
            return generator.result;
        };
        assertFailure(() -> service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)), ChatException.Reason.UNAUTHENTICATED);
        assertThat(store.answers).isEmpty();
        assertThat(store.resources).isEmpty();
    }

    @Test
    void revokedSessionDuringGenerationDoesNotPermitSaving() {
        generator.action = request -> {
            auth.revoked = true;
            return generator.result;
        };
        assertFailure(() -> service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)), ChatException.Reason.UNAUTHENTICATED);
        assertThat(store.answers).isEmpty();
    }

    @Test
    void missingPersistenceDoesNotPerformAnUnrecordedExternalGeneration() {
        ChatService service = new ChatService(Optional.of(auth), Optional.of(search()), Optional.of(generator), Optional.empty(), Optional.of(requestTimePolicy()), Optional.of(privacy), clock);
        assertFailure(() -> service.requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)), ChatException.Reason.DEPENDENCY_UNAVAILABLE);
        assertThat(searchCalls).hasValue(0);
        assertThat(generator.calls).isZero();
    }

    @Test
    void unavailableDatabaseCannotPretendTheAnswerWasSaved() {
        store.available = false;
        assertFailure(() -> service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)), ChatException.Reason.DEPENDENCY_UNAVAILABLE);
        assertThat(generator.calls).isZero();
    }

    @Test
    void persistenceErrorHasASafe500ContractInsteadOfFalseSuccess() {
        store.failure = new IllegalStateException("password=SECRET location=37.123 question=" + QUESTION);
        assertThatThrownBy(() -> service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)))
                .isInstanceOfSatisfying(ChatException.class, error -> {
                    assertThat(error.reason()).isEqualTo(ChatException.Reason.PERSISTENCE_FAILURE);
                    assertThat(error.reason().httpStatus()).isEqualTo(500);
                    assertThat(error.getMessage()).doesNotContain("SECRET", "37.123", QUESTION);
                    assertThat(error.getCause()).isNull();
                });
        assertThat(store.answers).isEmpty();
    }

    @Test
    void retentionDeletesAtTheNinetyDayBoundaryIncludingUsedResources() {
        service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));
        clock.advance(Duration.ofDays(90).minusNanos(1));
        assertThat(service().deleteExpiredAnswers()).isZero();
        assertThat(store.answers).hasSize(1);
        clock.advance(Duration.ofNanos(1));
        assertThat(service().deleteExpiredAnswers()).isEqualTo(1);
        assertThat(store.answers).isEmpty();
        assertThat(store.resources).isEmpty();
    }

    @Test
    void withdrawalDeletesOnlyTheMembersAnswersAndTheirLinksImmediately() {
        service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));
        UUID other = UUID.randomUUID();
        ChatAnswer otherAnswer = new ChatAnswer(UUID.randomUUID(), other, "다른 회원 질문", "답변", Status.COMPLETED, null, START, START, START.plus(Duration.ofDays(90)));
        store.save(otherAnswer, List.of(RESOURCE_TWO));

        assertThat(service().deleteAnswersOnWithdrawal(MEMBER)).isEqualTo(1);
        assertThat(store.answers.values()).containsExactly(otherAnswer);
        assertThat(store.resources).containsOnlyKeys(otherAnswer.getAnswerId());
    }

    @Test
    void questionPromptAndAnswerDiagnosticStringsExcludePrivateContent() {
        service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));
        assertThat(new ChatRequest(QUESTION).toString()).doesNotContain(QUESTION);
        assertThat(generator.request.toString()).doesNotContain(QUESTION, "공식 원문");
        assertThat(generator.result.toString()).doesNotContain(generator.result.text());
        assertThat(resource(RESOURCE_ONE).toString()).doesNotContain("공식 원문");
    }

    @Test
    void unresolvedRetentionPolicyDoesNotChooseBetweenTheConflictingDocumentsOrCallTheLlm() {
        ChatService service = new ChatService(Optional.of(auth), Optional.of(search()), Optional.of(generator),
                Optional.of(store), Optional.empty(), Optional.of(privacy), clock);
        assertFailure(() -> service.requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)), ChatException.Reason.POLICY_UNRESOLVED);
        assertThat(searchCalls).hasValue(0);
        assertThat(generator.calls).isZero();
        assertThat(store.answers).isEmpty();
    }

    @Test
    void unapprovedRetentionPolicyCannotStartGeneration() {
        ChatRetentionPolicy unapproved = new ChatRetentionPolicy() {
            @Override public boolean isApproved() { return false; }
            @Override public Instant approvedExpiresAt(Instant requestedAt, Instant completedAt) {
                throw new AssertionError("Unapproved policy cannot calculate expiry");
            }
        };
        ChatService service = new ChatService(Optional.of(auth), Optional.of(search()), Optional.of(generator),
                Optional.of(store), Optional.of(unapproved), Optional.of(privacy), clock);
        assertFailure(() -> service.requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION)), ChatException.Reason.POLICY_UNRESOLVED);
        assertThat(generator.calls).isZero();
    }

    @Test
    void requestAndCompletionReferencePoliciesProduceDifferentExpiriesWithoutAProductionDefault() {
        generator.action = request -> {
            clock.advance(Duration.ofMinutes(5));
            return generator.result;
        };
        service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));
        Instant requestTimeExpiry = store.onlyAnswer().getExpiresAt();
        assertThat(requestTimeExpiry).isEqualTo(START.plus(Duration.ofDays(90)));
        store.answers.clear();
        store.resources.clear();
        clock.now = START;
        ChatRetentionPolicy completionPolicy = new ChatRetentionPolicy() {
            @Override public boolean isApproved() { return true; }
            @Override public Instant approvedExpiresAt(Instant requestedAt, Instant completedAt) {
                return completedAt.plus(Duration.ofDays(90));
            }
        };
        ChatService service = new ChatService(Optional.of(auth), Optional.of(search()), Optional.of(generator),
                Optional.of(store), Optional.of(completionPolicy), Optional.of(privacy), clock);
        service.requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));
        assertThat(store.onlyAnswer().getExpiresAt()).isEqualTo(requestTimeExpiry.plus(Duration.ofMinutes(5)));
    }

    @Test
    void unnecessaryEmailPrecisionLocationAndCredentialsAreNotSentToSearchOrLlm() {
        String raw = "지진 대응 질문. 이메일 private@example.com 좌표 37.123456,127.987654 token SECRET_TOKEN";
        String safe = "지진 대응 행동요령";
        privacy.action = ignored -> Optional.of(new PreparedQuestion(safe, true));

        service().requestGuidance(MEMBER, SESSION, new ChatRequest(raw));

        assertThat(searchedQuestion).isEqualTo(safe).doesNotContain("private@example.com", "37.123456", "127.987654", "SECRET_TOKEN");
        assertThat(generator.request.question()).isEqualTo(safe).doesNotContain("private@example.com", "37.123456", "127.987654", "SECRET_TOKEN");
        assertThat(generator.request.evidence()).allMatch(OfficialResource::approvedPublicAiEvidence);
        assertThat(store.onlyAnswer().getQuestionText()).isEqualTo(raw);
        assertThat(new PreparedQuestion(raw, false).toString()).doesNotContain(raw, "private@example.com", "SECRET_TOKEN");
    }

    @Test
    void configuredOllamaAdapterStoresOnlyVerifiedQuotesAndReceivesThePrivacyApprovedQuestion() {
        String raw = "지진 질문 private@example.com SECRET_TOKEN";
        String safe = "지진 공식 행동요령";
        privacy.action = ignored -> Optional.of(new PreparedQuestion(safe, true));
        OllamaChatClient client = configuredLocalClient();
        String selected = resource(RESOURCE_TWO).content();
        when(client.chat(anyString(), anyString(), anyMap(), eq(START.plus(ChatService.RESPONSE_LIMIT))))
                .thenAnswer(invocation -> {
                    var payload = OllamaJson.readObject(invocation.getArgument(1, String.class));
                    assertThat(payload.get("question").asString()).isEqualTo(safe);
                    assertThat(invocation.getArgument(1, String.class)).doesNotContain("private@example.com", "SECRET_TOKEN");
                    return new OllamaChatClient.Response(localAnswer(selected, RESOURCE_TWO), "configured-local-model", Duration.ofMillis(1));
                });

        ChatResponse response = localService(client).requestGuidance(MEMBER, SESSION, new ChatRequest(raw));

        assertThat(response.answerType()).isEqualTo(AnswerType.AI);
        assertThat(response.answer()).isEqualTo(selected);
        assertThat(response.sources()).containsExactly(new ChatResponse.Source("공식기관", "공식 행동요령"));
        assertThat(store.onlyAnswer().getModelName()).isEqualTo("configured-local-model");
        assertThat(store.resources.get(store.onlyAnswer().getAnswerId())).containsExactly(RESOURCE_TWO);
        assertThat(store.onlyAnswer().getQuestionText()).isEqualTo(raw);
    }

    @Test
    void unverifiedOllamaOutputIsReplacedAndNeverSavedAsAnAiAnswer() {
        OllamaChatClient client = configuredLocalClient();
        when(client.chat(anyString(), anyString(), anyMap(), eq(START.plus(ChatService.RESPONSE_LIMIT))))
                .thenReturn(new OllamaChatClient.Response(localAnswer("37.123456은 무조건 안전합니다. SECRET_TOKEN", RESOURCE_ONE),
                        "configured-local-model", Duration.ofMillis(1)));

        ChatResponse response = localService(client).requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));

        assertThat(response.answerType()).isEqualTo(AnswerType.FALLBACK);
        assertThat(response.answer()).doesNotContain("37.123456", "SECRET_TOKEN", "무조건 안전");
        assertThat(response.sources()).isEmpty();
        assertThat(store.onlyAnswer().getStatus()).isEqualTo(Status.FAILED);
        assertThat(store.onlyAnswer().getModelName()).isNull();
        assertThat(store.resources.get(store.onlyAnswer().getAnswerId())).isEmpty();
    }

    @Test
    void anOllamaAnswerAfterTheSharedDeadlineIsStoredAsTimeoutFallback() {
        OllamaChatClient client = configuredLocalClient();
        when(client.chat(anyString(), anyString(), anyMap(), eq(START.plus(ChatService.RESPONSE_LIMIT))))
                .thenAnswer(invocation -> {
                    clock.advance(ChatService.RESPONSE_LIMIT);
                    return new OllamaChatClient.Response(localAnswer(resource(RESOURCE_ONE).content(), RESOURCE_ONE),
                            "configured-local-model", Duration.ofMinutes(10));
                });

        ChatResponse response = localService(client).requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));

        assertThat(response.answerType()).isEqualTo(AnswerType.FALLBACK);
        assertThat(store.onlyAnswer().getStatus()).isEqualTo(Status.TIMEOUT);
        assertThat(store.onlyAnswer().getModelName()).isNull();
        assertThat(store.resources.get(store.onlyAnswer().getAnswerId())).isEmpty();
    }

    @Test
    void missingPrivacyMinimizationNeverSendsRawQuestionToAnyExternalPort() {
        ChatService service = new ChatService(Optional.of(auth), Optional.of(search()), Optional.of(generator),
                Optional.of(store), Optional.of(requestTimePolicy()), Optional.empty(), clock);
        ChatResponse response = service.requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));

        assertThat(response.answerType()).isEqualTo(AnswerType.FALLBACK);
        assertThat(response.sources()).isEmpty();
        assertThat(store.onlyAnswer().getStatus()).isEqualTo(Status.FALLBACK);
        assertThat(searchCalls).hasValue(0);
        assertThat(generator.calls).isZero();
    }

    @ParameterizedTest
    @MethodSource("unapprovedQuestions")
    void failedOrUnapprovedPrivacyMinimizationCannotInvokeSearchOrTheLlm(Optional<PreparedQuestion> prepared) {
        privacy.action = ignored -> prepared;
        assertFallback(Status.FALLBACK);
        assertThat(searchCalls).hasValue(0);
        assertThat(generator.calls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00a0", "\u2007", "\u202f"})
    void privacyMinimizationCannotApproveAWhitespaceOnlyQuestionForExternalTransmission(String question) {
        privacy.action = ignored -> Optional.of(new PreparedQuestion(question, true));

        assertFallback(Status.FALLBACK);
        assertThat(searchCalls).hasValue(0);
        assertThat(generator.calls).isZero();
    }

    static Stream<Optional<PreparedQuestion>> unapprovedQuestions() {
        return Stream.of(Optional.empty(), Optional.of(new PreparedQuestion(QUESTION, false)),
                Optional.of(new PreparedQuestion(null, true)), Optional.of(new PreparedQuestion(" ", true)));
    }

    @Test
    void privacyMinimizationFailureDoesNotLeakRawQuestionOrCredentials() {
        privacy.action = raw -> { throw new IllegalStateException("token=SECRET latitude=37.123456 " + raw); };
        ChatResponse response = assertFallback(Status.FAILED);
        assertThat(response.answer()).doesNotContain("SECRET", "37.123456", QUESTION);
        assertThat(searchCalls).hasValue(0);
        assertThat(generator.calls).isZero();
    }

    private ChatResponse assertFallback(Status expectedStatus) {
        ChatResponse response = service().requestGuidance(MEMBER, SESSION, new ChatRequest(QUESTION));
        assertThat(response.answerType()).isEqualTo(AnswerType.FALLBACK);
        assertThat(response.answer()).isEqualTo("공식기관에서 제공하는 재난 행동요령을 확인하세요.");
        assertThat(response.sources()).isEmpty();
        assertThat(store.onlyAnswer().getStatus()).isEqualTo(expectedStatus);
        assertThat(store.resources.get(store.onlyAnswer().getAnswerId())).isEmpty();
        return response;
    }

    private ChatService service() {
        return new ChatService(Optional.of(auth), Optional.of(search()), Optional.of(generator), Optional.of(store), Optional.of(requestTimePolicy()), Optional.of(privacy), clock);
    }

    private ChatService localService(OllamaChatClient client) {
        return new ChatService(Optional.of(auth), Optional.of(search()),
                Optional.of(new OllamaChatAnswerGenerator(client, clock)), Optional.of(store),
                Optional.of(requestTimePolicy()), Optional.of(privacy), clock);
    }

    private static OllamaChatClient configuredLocalClient() {
        OllamaChatClient client = mock(OllamaChatClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.modelName()).thenReturn("configured-local-model");
        return client;
    }

    private static String localAnswer(String text, UUID resourceId) {
        return OllamaJson.write(Map.of("answer", text, "sufficientEvidence", true,
                "usedResourceIds", List.of(resourceId.toString()),
                "citations", List.of(Map.of("resourceId", resourceId.toString(), "excerpt", text))));
    }


    /** Fake-only reference policy, explicitly not a production resolution of the document conflict. */
    private static ChatRetentionPolicy requestTimePolicy() {
        return new ChatRetentionPolicy() {
            @Override public boolean isApproved() { return true; }
            @Override public Instant approvedExpiresAt(Instant requestedAt, Instant completedAt) {
                return requestedAt.plus(Duration.ofDays(90));
            }
        };
    }

    private ChatOfficialResourceSearchPort search() {
        return question -> {
            searchCalls.incrementAndGet();
            searchedQuestion = question;
            return searchAction.apply(question);
        };
    }

    private static OfficialResource resource(UUID id) {
        return new OfficialResource(id, "공식기관", "공식 행동요령", URI.create("https://agency.example/guide"),
                "제공처에서 조회한 공식 원문. 공식 위험등급은 변경하지 않는다.", true, true, true);
    }

    private static void assertFailure(Runnable action, ChatException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ChatException.class,
                error -> assertThat(error.reason()).isEqualTo(reason));
    }

    enum SessionFailure { INACTIVE, EXPIRED, REVOKED, OTHER_MEMBER }

    private static final class FakePrivacy implements ChatPromptPrivacyPort {
        Function<String, Optional<PreparedQuestion>> action = raw -> Optional.of(new PreparedQuestion(raw, true));

        @Override
        public Optional<PreparedQuestion> minimizeLocally(String rawQuestion) {
            return action.apply(rawQuestion);
        }
    }

    private static final class FakeAuthentication implements ChatAuthenticationPort {
        UUID member = MEMBER;
        boolean active = true;
        boolean expired;
        boolean revoked;
        int checks;

        @Override
        public synchronized void requireActiveSession(UUID memberId, UUID sessionId) {
            checks++;
            if (!member.equals(memberId) || !SESSION.equals(sessionId) || !active || expired || revoked) {
                throw new ChatException(ChatException.Reason.UNAUTHENTICATED, "Invalid active member session.");
            }
        }

        @Override
        public synchronized void runWithActiveSession(UUID memberId, UUID sessionId, Runnable action) {
            requireActiveSession(memberId, sessionId);
            action.run();
        }
    }

    private static final class FakeGenerator implements ChatAnswerGenerationPort {
        boolean configured = true;
        BooleanSupplier configurationAction = () -> configured;
        int calls;
        GenerationRequest request;
        RuntimeException failure;
        GeneratedAnswer result = new GeneratedAnswer("공식자료에서 확인한 대응 안내", List.of(RESOURCE_ONE), true, null);
        Function<GenerationRequest, GeneratedAnswer> action = ignored -> result;

        @Override
        public boolean isConfigured() { return configurationAction.getAsBoolean(); }

        @Override
        public GeneratedAnswer generate(GenerationRequest request) {
            calls++;
            this.request = request;
            if (failure != null) {
                throw failure;
            }
            return action.apply(request);
        }
    }

    private static final class FakeStore implements ChatAnswerStore {
        final Map<UUID, ChatAnswer> answers = new LinkedHashMap<>();
        final Map<UUID, List<UUID>> resources = new LinkedHashMap<>();
        boolean available = true;
        RuntimeException failure;

        @Override
        public boolean isAvailable() { return available; }

        @Override
        public void save(ChatAnswer answer, List<UUID> ids) {
            if (failure != null) { throw failure; }
            answers.put(answer.getAnswerId(), answer);
            resources.put(answer.getAnswerId(), List.copyOf(ids));
        }

        @Override
        public int deleteExpired(Instant now) {
            List<UUID> deleted = answers.values().stream().filter(answer -> !answer.getExpiresAt().isAfter(now))
                    .map(ChatAnswer::getAnswerId).toList();
            deleted.forEach(id -> { answers.remove(id); resources.remove(id); });
            return deleted.size();
        }

        @Override
        public int deleteByMemberId(UUID memberId) {
            List<UUID> deleted = new ArrayList<>(answers.values().stream().filter(answer -> answer.getMemberId().equals(memberId))
                    .map(ChatAnswer::getAnswerId).toList());
            deleted.forEach(id -> { answers.remove(id); resources.remove(id); });
            return deleted.size();
        }

        ChatAnswer onlyAnswer() {
            assertThat(answers).hasSize(1);
            return answers.values().iterator().next();
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

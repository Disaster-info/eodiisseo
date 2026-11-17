package com.disasterinfo.eodiisseo.ai.summary;

import com.disasterinfo.eodiisseo.ai.summary.port.SummaryAuthPort;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryFailureRecorder;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryGenerator;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryRetryPolicy;
import com.disasterinfo.eodiisseo.ai.summary.port.SummarySourceReader;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryStore;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryTaskDispatcher;
import com.disasterinfo.eodiisseo.ai.summary.adapter.OllamaSummaryGenerator;
import com.disasterinfo.eodiisseo.ai.ollama.OllamaChatClient;
import com.disasterinfo.eodiisseo.ai.ollama.OllamaJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Fake external services exist only here; none are application beans. */
class SummaryServiceTest {
    private static final UUID MEMBER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SESSION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DISASTER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SOURCE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID REVISION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");

    private FakeAuth auth;
    private FakeSource sources;
    private FakeStore store;
    private FakeGenerator generator;
    private FakeDispatcher dispatcher;
    private List<SummaryFailureRecorder.Code> failureCodes;
    private Optional<SummaryRetryPolicy.Policy> policy;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        auth = new FakeAuth();
        sources = new FakeSource();
        store = new FakeStore();
        store.lifecycle = () -> sources.ended;
        generator = new FakeGenerator();
        dispatcher = new FakeDispatcher();
        failureCodes = new ArrayList<>();
        policy = Optional.of(new SummaryRetryPolicy.Policy(2, Duration.ofSeconds(30)));
        clock = new MutableClock(NOW);
        sources.current = revision(REVISION, 1, "Exact official provider text.");
        sources.revisions.put(REVISION, sources.current);
    }

    @Test
    void missingMemberOrSessionFailsBeforeAnyDisasterRead() {
        assertReason(() -> service().getSummary(null, SESSION, DISASTER), SummaryException.Reason.UNAUTHENTICATED);
        assertReason(() -> service().getSummary(MEMBER, null, DISASTER), SummaryException.Reason.UNAUTHENTICATED);
        assertThat(sources.disasterReads).isZero();
    }

    @Test
    void inactiveMemberFailsClosed() {
        auth.active = false;
        assertReason(() -> service().getSummary(MEMBER, SESSION, DISASTER), SummaryException.Reason.UNAUTHENTICATED);
        assertThat(sources.disasterReads).isZero();
    }

    @Test
    void expiredSessionFailsClosed() {
        auth.expired = true;
        assertReason(() -> service().getSummary(MEMBER, SESSION, DISASTER), SummaryException.Reason.UNAUTHENTICATED);
    }

    @Test
    void revokedSessionFailsClosed() {
        auth.revoked = true;
        assertReason(() -> service().getSummary(MEMBER, SESSION, DISASTER), SummaryException.Reason.UNAUTHENTICATED);
    }

    @Test
    void sessionOwnedByAnotherMemberFailsClosed() {
        assertReason(() -> service().getSummary(UUID.randomUUID(), SESSION, DISASTER),
                SummaryException.Reason.UNAUTHENTICATED);
    }

    @Test
    void authenticationIsRecheckedForEachRead() {
        service().getSummary(MEMBER, SESSION, DISASTER);
        auth.revoked = true;
        assertReason(() -> service().getSummary(MEMBER, SESSION, DISASTER), SummaryException.Reason.UNAUTHENTICATED);
        assertThat(auth.calls).isEqualTo(2);
    }

    @Test
    void missingDisasterReturnsNotFoundInsteadOfInventingSummary() {
        sources.exists = false;
        assertReason(() -> service().getSummary(MEMBER, SESSION, DISASTER), SummaryException.Reason.NOT_FOUND);
    }

    @Test
    void mockDisasterIsHiddenFromOperationalSummaryRead() {
        sources.origin = OfficialRevision.Origin.MOCK;
        sources.current = null;
        assertReason(() -> service().getSummary(MEMBER, SESSION, DISASTER), SummaryException.Reason.NOT_FOUND);
    }

    @Test
    void absentSummaryReturnsPendingWithoutStartingGeneration() {
        var response = service().getSummary(MEMBER, SESSION, DISASTER);
        assertThat(response.status()).isEqualTo(SummaryStatus.PENDING);
        assertThat(response.summary()).isNull();
        assertThat(response.generatedAt()).isNull();
        assertThat(generator.calls).isZero();
        assertThat(dispatcher.tasks).isEmpty();
        assertThat(store.states).isEmpty();
    }

    @Test
    void absentOfficialRevisionDoesNotGenerateOrGuessContent() {
        sources.current = null;
        assertThat(service().getSummary(MEMBER, SESSION, DISASTER).status()).isEqualTo(SummaryStatus.PENDING);
    }

    @Test
    void registrationQueuesWorkWithoutCallingAi() {
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW)).isEqualTo(SummaryService.Submission.QUEUED);
        assertThat(store.states.get(REVISION).status()).isEqualTo(SummaryStatus.PENDING);
        assertThat(generator.calls).isZero();
        assertThat(dispatcher.tasks).containsExactly(new Task(REVISION, NOW));
    }

    @Test
    void duplicateSourceEventPreservesOneRevisionJobAndOriginalCreationTime() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        service().onOfficialRevisionSaved(REVISION, NOW.plusSeconds(1));
        assertThat(store.states).hasSize(1);
        assertThat(store.states.get(REVISION).createdAt()).isEqualTo(NOW);
    }

    @Test
    void successfulBackgroundWorkCompletesExactCurrentRevision() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        assertThat(service().processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.COMPLETED);
        var response = service().getSummary(MEMBER, SESSION, DISASTER);
        assertThat(response.status()).isEqualTo(SummaryStatus.COMPLETED);
        assertThat(response.summary()).isEqualTo("Supported AI summary.");
        assertThat(response.generatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(generator.lastInput).isEqualTo(sources.current);
        assertThat(generator.lastInput.officialSeverity()).isEqualTo("WARNING");
        assertThat(store.states.get(REVISION).attemptCount()).isZero();
    }

    @Test
    void configuredOllamaAdapterCompletesThroughTheExistingWorkerAndResponseContracts() {
        var client = mock(OllamaChatClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.modelName()).thenReturn("audit-model");
        when(client.chat(anyString(), anyString(), anyMap(), isNull())).thenReturn(new OllamaChatClient.Response(
                OllamaJson.write(Map.of("excerpts", List.of(sources.current.originalText()))), "audit-model", Duration.ofMillis(5)));
        var summary = service(new OllamaSummaryGenerator(client, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)));
        assertThat(summary.onOfficialRevisionSaved(REVISION, NOW)).isEqualTo(SummaryService.Submission.QUEUED);
        assertThat(summary.processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.COMPLETED);
        var response = summary.getSummary(MEMBER, SESSION, DISASTER);
        assertThat(response.summary()).contains("공식 위험등급: WARNING", sources.current.originalText());
        assertThat(response.generatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(store.states.get(REVISION).modelName()).isEqualTo("audit-model");
        assertThat(failureCodes).isEmpty();
    }

    @Test
    void unsupportedOllamaOutputFailsTheJobAndPreservesTheOfficialOriginal() {
        var client = mock(OllamaChatClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.modelName()).thenReturn("audit-model");
        when(client.chat(anyString(), anyString(), anyMap(), isNull())).thenReturn(new OllamaChatClient.Response(
                OllamaJson.write(Map.of("excerpts", List.of("A fictional region is safe at an invented time."))),
                "audit-model", Duration.ofMillis(5)));
        var summary = service(new OllamaSummaryGenerator(client, clock));
        assertThat(summary.onOfficialRevisionSaved(REVISION, NOW)).isEqualTo(SummaryService.Submission.QUEUED);
        assertThat(summary.processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.FAILED);
        assertThat(summary.getSummary(MEMBER, SESSION, DISASTER).summary()).isNull();
        assertThat(sources.current.originalText()).isEqualTo("Exact official provider text.");
        assertThat(failureCodes).containsExactly(SummaryFailureRecorder.Code.GENERATION_FAILED);
    }

    @Test
    void repeatedWorkerDeliveryNeverMakesAnotherAiCallAfterCompletion() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        service().processRevision(REVISION, NOW);
        assertThat(service().processRevision(REVISION, NOW.plusSeconds(10)))
                .isEqualTo(SummaryService.Processing.NOT_ELIGIBLE);
        assertThat(generator.calls).isEqualTo(1);
    }

    @Test
    void originalChangeStalesPreviousResultAndQueuesIndependentJob() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        service().processRevision(REVISION, NOW);
        UUID nextId = UUID.randomUUID();
        sources.current = revision(nextId, 2, "Changed exact official text.");
        sources.revisions.put(nextId, sources.current);
        assertThat(service().onOfficialRevisionSaved(nextId, NOW.plusSeconds(10)))
                .isEqualTo(SummaryService.Submission.QUEUED);
        assertThat(store.states.get(REVISION).status()).isEqualTo(SummaryStatus.STALE);
        assertThat(store.states.get(nextId).status()).isEqualTo(SummaryStatus.PENDING);
        assertThat(service().getSummary(MEMBER, SESSION, DISASTER).summary()).isNull();
    }

    @Test
    void delayedSourceEventCannotRegenerateAnOlderRevision() {
        UUID nextId = UUID.randomUUID();
        sources.current = revision(nextId, 2, "Updated original.");
        sources.revisions.put(nextId, sources.current);
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW)).isEqualTo(SummaryService.Submission.IGNORED);
        assertThat(store.states).isEmpty();
    }

    @Test
    void readImmediatelyAfterSourceChangeCannotExposeOldSummaryAsCurrent() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        service().processRevision(REVISION, NOW);
        sources.current = revision(UUID.randomUUID(), 2, "Newer official text, event not delivered yet.");
        var response = service().getSummary(MEMBER, SESSION, DISASTER);
        assertThat(response.status()).isEqualTo(SummaryStatus.STALE);
        assertThat(response.summary()).isNull();
        assertThat(response.generatedAt()).isNull();
    }

    @Test
    void sourceChangingDuringSummaryReadHidesTheFormerCurrentText() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        service().processRevision(REVISION, NOW);
        sources.afterDisasterRead = () -> sources.current = revision(UUID.randomUUID(), 2, "New original during read.");
        var response = service().getSummary(MEMBER, SESSION, DISASTER);
        assertThat(response.status()).isEqualTo(SummaryStatus.STALE);
        assertThat(response.summary()).isNull();
    }

    @Test
    void lookupReturningAnotherRevisionCannotGenerateContentForRequestedUuid() {
        sources.revisions.put(REVISION, revision(UUID.randomUUID(), 2, "Incorrect reader result."));
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW)).isEqualTo(SummaryService.Submission.IGNORED);
        assertThat(store.states).isEmpty();
        assertThat(generator.calls).isZero();
    }

    @Test
    void revisionChangingDuringAiCallDiscardsTheGeneratedOldResult() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = input -> {
            sources.current = revision(UUID.randomUUID(), 2, "Changed during generation.");
            return new SummaryGenerator.Generated("Old result must be discarded.", "test-model", NOW.plusSeconds(1));
        };
        assertThat(service().processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.STALE);
        assertThat(store.states.get(REVISION).summaryText()).isNull();
    }

    @Test
    void aiExceptionReturnsFailedWithOnlySafeFailureCode() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> { throw new IllegalStateException("API_KEY=secret precise location=secret"); };
        assertThat(service().processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.FAILED);
        var response = service().getSummary(MEMBER, SESSION, DISASTER);
        assertThat(response.status()).isEqualTo(SummaryStatus.FAILED);
        assertThat(response.summary()).isNull();
        assertThat(failureCodes).containsExactly(SummaryFailureRecorder.Code.GENERATION_FAILED);
    }

    @Test
    void configuredRetryBoundStopsAfterInitialCallAndTwoRetries() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> { throw new IllegalStateException("External failure"); };
        service().processRevision(REVISION, NOW);
        clock.current = NOW.plusSeconds(30);
        service().processRevision(REVISION, NOW.plusSeconds(30));
        clock.current = NOW.plusSeconds(60);
        service().processRevision(REVISION, NOW.plusSeconds(60));
        clock.current = NOW.plusSeconds(90);
        assertThat(service().processRevision(REVISION, NOW.plusSeconds(90)))
                .isEqualTo(SummaryService.Processing.NOT_ELIGIBLE);
        assertThat(generator.calls).isEqualTo(3);
        assertThat(store.states.get(REVISION).attemptCount()).isEqualTo(2);
        assertThat(dispatcher.tasks).containsExactly(new Task(REVISION, NOW),
                new Task(REVISION, NOW.plusSeconds(30)), new Task(REVISION, NOW.plusSeconds(60)));
    }

    @Test
    void duplicateFailedSourceEventCannotReenqueueAfterRetryDispatchOutage() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        dispatcher.fail = true;
        generator.answer = ignored -> {
            clock.current = NOW.plusSeconds(10);
            throw new IllegalStateException("External failure");
        };
        assertThat(service().processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.FAILED);
        assertThat(store.states.get(REVISION).completedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(service().getSummary(MEMBER, SESSION, DISASTER).generatedAt()).isNull();
        dispatcher.fail = false;
        clock.current = NOW.plusSeconds(11);
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW.plusSeconds(11)))
                .isEqualTo(SummaryService.Submission.ALREADY_HANDLED);
        assertThat(service().processRevision(REVISION, NOW.plusSeconds(11)))
                .isEqualTo(SummaryService.Processing.NOT_ELIGIBLE);
        assertThat(dispatcher.tasks).containsExactly(new Task(REVISION, NOW));
        assertThat(generator.calls).isEqualTo(1);
        assertThat(failureCodes).contains(SummaryFailureRecorder.Code.DISPATCH_FAILED);
    }

    @Test
    void duplicateWorkerCannotRetryBeforeDelayFromRealFailureCompletion() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> {
            clock.current = NOW.plusSeconds(10);
            throw new IllegalStateException("External failure");
        };
        service().processRevision(REVISION, NOW);
        assertThat(dispatcher.tasks).containsExactly(new Task(REVISION, NOW), new Task(REVISION, NOW.plusSeconds(40)));
        clock.current = NOW.plusSeconds(39);
        assertThat(service().processRevision(REVISION, NOW.plusSeconds(39)))
                .isEqualTo(SummaryService.Processing.NOT_ELIGIBLE);
        assertThat(generator.calls).isEqualTo(1);
        clock.current = NOW.plusSeconds(40);
        generator.answer = ignored -> {
            clock.current = NOW.plusSeconds(41);
            return new SummaryGenerator.Generated("Supported new result", "test-model", clock.instant());
        };
        assertThat(service().processRevision(REVISION, NOW.plusSeconds(40)))
                .isEqualTo(SummaryService.Processing.COMPLETED);
        assertThat(generator.calls).isEqualTo(2);
        assertThat(store.states.get(REVISION).attemptCount()).isEqualTo(1);
    }

    @Test
    void futureWorkerTimestampCannotPretendThatRetryDelayHasElapsed() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> { throw new IllegalStateException("External failure"); };
        service().processRevision(REVISION, NOW);
        assertThat(service().processRevision(REVISION, NOW.plusSeconds(30)))
                .isEqualTo(SummaryService.Processing.NOT_ELIGIBLE);
        assertThat(generator.calls).isEqualTo(1);
    }

    @Test
    void legacyFailureWithoutCompletionTimeIsHeldWithoutInventingItsRetryDeadline() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        SummaryState pending = store.states.get(REVISION);
        store.states.put(REVISION, new SummaryState(pending.summaryId(), DISASTER, REVISION,
                SummaryStatus.FAILED, null, null, 0, NOW, null));
        clock.current = NOW.plusSeconds(500);
        assertThat(service().processRevision(REVISION, NOW.plusSeconds(500)))
                .isEqualTo(SummaryService.Processing.NOT_ELIGIBLE);
        assertThat(generator.calls).isZero();
    }

    @Test
    void policyCanDisableRetriesExplicitlyWithoutBlockingInitialAttempt() {
        policy = Optional.of(new SummaryRetryPolicy.Policy(0, Duration.ZERO));
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> { throw new IllegalStateException(); };
        service().processRevision(REVISION, NOW);
        assertThat(service().processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.NOT_ELIGIBLE);
        assertThat(generator.calls).isEqualTo(1);
        assertThat(dispatcher.tasks).hasSize(1);
    }

    @Test
    void absentRetryPolicyCreatesPendingJobButDefersExternalCalls() {
        policy = Optional.empty();
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW))
                .isEqualTo(SummaryService.Submission.POLICY_UNCONFIGURED);
        assertThat(service().processRevision(REVISION, NOW))
                .isEqualTo(SummaryService.Processing.POLICY_UNCONFIGURED);
        assertThat(store.states.get(REVISION).status()).isEqualTo(SummaryStatus.PENDING);
        assertThat(dispatcher.tasks).isEmpty();
        assertThat(generator.calls).isZero();
    }

    @Test
    void sourceWithoutOfficialOriginalIsDeferredWithoutAiCall() {
        sources.current = revision(REVISION, 1, null);
        sources.revisions.put(REVISION, sources.current);
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW))
                .isEqualTo(SummaryService.Submission.SOURCE_INCOMPLETE);
        assertThat(store.states).isEmpty();
        assertThat(generator.calls).isZero();
    }

    @Test
    void mockSourceCannotRegisterOperationalAiWork() {
        OfficialRevision mock = new OfficialRevision(DISASTER, SOURCE, REVISION, 1, "HEAVY_RAIN", null,
                OfficialRevision.DisasterStatus.ONGOING, null, "Test original", NOW, OfficialRevision.Origin.MOCK);
        sources.current = mock;
        sources.revisions.put(REVISION, mock);
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW)).isEqualTo(SummaryService.Submission.IGNORED);
        assertThat(store.states).isEmpty();
    }

    @Test
    void dispatcherFailureDoesNotEscapeIntoOfficialSourceFlow() {
        dispatcher.fail = true;
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW))
                .isEqualTo(SummaryService.Submission.DEPENDENCY_UNAVAILABLE);
        assertThat(store.states.get(REVISION).status()).isEqualTo(SummaryStatus.PENDING);
        assertThat(failureCodes).containsExactly(SummaryFailureRecorder.Code.REGISTRATION_FAILED);
    }

    @Test
    void absentGeneratorKeepsPendingDataWithoutPretendingToCallAi() {
        SummaryService disconnected = new SummaryService(Optional.of(auth), Optional.of(sources), Optional.of(store),
                Optional.empty(), Optional.of(dispatcher), Optional.of(() -> policy), Optional.empty());
        assertThat(disconnected.onOfficialRevisionSaved(REVISION, NOW))
                .isEqualTo(SummaryService.Submission.DEPENDENCY_UNAVAILABLE);
        assertThat(store.states).hasSize(1);
        assertThat(generator.calls).isZero();
    }

    @Test
    void generatorBeanWithoutCredentialsOrTimeoutCannotCallTheExternalModel() {
        generator.configured = false;
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW))
                .isEqualTo(SummaryService.Submission.DEPENDENCY_UNAVAILABLE);
        assertThat(service().processRevision(REVISION, NOW))
                .isEqualTo(SummaryService.Processing.DEPENDENCY_UNAVAILABLE);
        assertThat(store.states.get(REVISION).status()).isEqualTo(SummaryStatus.PENDING);
        assertThat(dispatcher.tasks).isEmpty();
        assertThat(generator.calls).isZero();
    }

    @Test
    void failureRecorderOutageCannotPropagateAiFailure() {
        SummaryService failingRecorder = new SummaryService(Optional.of(auth), Optional.of(sources), Optional.of(store),
                Optional.of(generator), Optional.of(dispatcher), Optional.of(() -> policy),
                Optional.of((code, disaster, revision, at) -> { throw new IllegalStateException("secret"); }), clock);
        failingRecorder.onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> { throw new IllegalStateException("secret"); };
        assertThat(failingRecorder.processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.FAILED);
    }

    @Test
    void emptyAiResultFailsInsteadOfGeneratingPlaceholderSummary() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> new SummaryGenerator.Generated(" ", "test-model", NOW.plusSeconds(1));
        assertThat(service().processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.FAILED);
        assertThat(failureCodes).containsExactly(SummaryFailureRecorder.Code.INVALID_AI_RESULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00A0", "\u2007", "\u202F"})
    void unicodeSpaceOnlyOriginalCannotRegisterOrCallAi(String whitespace) {
        sources.current = revision(REVISION, 1, whitespace);
        sources.revisions.put(REVISION, sources.current);
        assertThat(service().onOfficialRevisionSaved(REVISION, NOW))
                .isEqualTo(SummaryService.Submission.SOURCE_INCOMPLETE);
        assertThat(store.states).isEmpty();
        assertThat(dispatcher.tasks).isEmpty();
        assertThat(generator.calls).isZero();
    }

    @Test
    void unicodeSpaceOnlyGeneratedSummaryCannotBecomeCompleted() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> new SummaryGenerator.Generated("\u00A0", "test-model", NOW.plusSeconds(1));
        assertThat(service().processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.FAILED);
        assertThat(service().getSummary(MEMBER, SESSION, DISASTER).summary()).isNull();
        assertThat(failureCodes).containsExactly(SummaryFailureRecorder.Code.INVALID_AI_RESULT);
    }

    @Test
    void unicodeSpaceOnlyModelNameCannotBecomeCompleted() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> new SummaryGenerator.Generated("Supported text", "\u2007", NOW.plusSeconds(1));
        assertThat(service().processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.FAILED);
        assertThat(store.states.get(REVISION).status()).isEqualTo(SummaryStatus.FAILED);
        assertThat(failureCodes).containsExactly(SummaryFailureRecorder.Code.INVALID_AI_RESULT);
    }

    @Test
    void invalidModelNameFailsBeforeAttemptingToStoreOverlengthValue() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        generator.answer = ignored -> new SummaryGenerator.Generated("Supported text", "m".repeat(101), NOW.plusSeconds(1));
        assertThat(service().processRevision(REVISION, NOW)).isEqualTo(SummaryService.Processing.FAILED);
    }

    @Test
    void cleanupKeepsEndedSummaryUntilRealNinetyDayDeadline() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        sources.ended = List.of(new SummarySourceReader.EndedDisaster(DISASTER,
                OfficialRevision.DisasterStatus.ENDED, NOW));
        assertThat(service().purgeExpiredSummaries(NOW.plus(SummaryService.RETENTION).minusNanos(1))).isZero();
        assertThat(store.states).hasSize(1);
        assertThat(service().purgeExpiredSummaries(NOW.plus(SummaryService.RETENTION))).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = OfficialRevision.DisasterStatus.class, names = {"OCCURRED", "ONGOING"})
    void cleanupNeverDeletesActiveDisasterBasedOnOldEndTimestamp(OfficialRevision.DisasterStatus status) {
        service().onOfficialRevisionSaved(REVISION, NOW);
        sources.ended = List.of(new SummarySourceReader.EndedDisaster(DISASTER, status, NOW.minus(Duration.ofDays(91))));
        assertThat(service().purgeExpiredSummaries(NOW)).isZero();
    }

    @Test
    void cleanupNeverInventsMissingEndTimestamp() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        sources.ended = List.of(new SummarySourceReader.EndedDisaster(DISASTER,
                OfficialRevision.DisasterStatus.LIFTED, null));
        assertThat(service().purgeExpiredSummaries(NOW.plus(Duration.ofDays(200)))).isZero();
    }

    @Test
    void cleanupCannotDeleteAfterTheDisasterBecomesActiveDuringTheCleanupWindow() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        sources.ended = List.of(new SummarySourceReader.EndedDisaster(DISASTER,
                OfficialRevision.DisasterStatus.ENDED, NOW.minus(Duration.ofDays(91))));
        store.beforeDelete = () -> sources.ended = List.of(new SummarySourceReader.EndedDisaster(DISASTER,
                OfficialRevision.DisasterStatus.ONGOING, NOW.minus(Duration.ofDays(91))));
        assertThat(service().purgeExpiredSummaries(NOW)).isZero();
        assertThat(store.states).hasSize(1);
        assertThat(store.lastEndCutoff).isEqualTo(NOW.minus(SummaryService.RETENTION));
    }

    @Test
    void cleanupCannotDeleteWhenTheOfficialEndTimeWasCorrectedAfterCandidateSelection() {
        service().onOfficialRevisionSaved(REVISION, NOW);
        sources.ended = List.of(new SummarySourceReader.EndedDisaster(DISASTER,
                OfficialRevision.DisasterStatus.LIFTED, NOW.minus(Duration.ofDays(91))));
        store.beforeDelete = () -> sources.ended = List.of(new SummarySourceReader.EndedDisaster(DISASTER,
                OfficialRevision.DisasterStatus.LIFTED, NOW.minus(Duration.ofDays(89))));
        assertThat(service().purgeExpiredSummaries(NOW)).isZero();
        assertThat(store.states).hasSize(1);
    }

    @Test
    void unsupportedRevisionOwnershipIsRejectedBySourceContract() {
        OfficialRevision wrong = new OfficialRevision(UUID.randomUUID(), SOURCE, REVISION, 1, "HEAVY_RAIN", null,
                OfficialRevision.DisasterStatus.ONGOING, null, "Official text", NOW, OfficialRevision.Origin.OFFICIAL);
        assertThatThrownBy(() -> new SummarySourceReader.Source(DISASTER, OfficialRevision.Origin.OFFICIAL,
                Optional.of(wrong))).isInstanceOf(IllegalArgumentException.class);
    }

    private SummaryService service() {
        return service(generator);
    }

    private SummaryService service(SummaryGenerator selectedGenerator) {
        return new SummaryService(Optional.of(auth), Optional.of(sources), Optional.of(store), Optional.of(selectedGenerator),
                Optional.of(dispatcher), Optional.of(() -> policy),
                Optional.of((code, disaster, revision, at) -> failureCodes.add(code)), clock);
    }

    private static OfficialRevision revision(UUID id, int number, String text) {
        return new OfficialRevision(DISASTER, SOURCE, id, number, "HEAVY_RAIN", "Official region",
                OfficialRevision.DisasterStatus.ONGOING, "WARNING", text, NOW, OfficialRevision.Origin.OFFICIAL);
    }

    private static void assertReason(Runnable action, SummaryException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SummaryException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static class FakeAuth implements SummaryAuthPort {
        boolean active = true;
        boolean expired;
        boolean revoked;
        int calls;
        @Override public void requireActiveSession(UUID member, UUID session) {
            calls++;
            if (!active || expired || revoked || !MEMBER.equals(member) || !SESSION.equals(session)) {
                throw new SummaryException(SummaryException.Reason.UNAUTHENTICATED);
            }
        }
    }

    private static class FakeSource implements SummarySourceReader {
        boolean exists = true;
        OfficialRevision.Origin origin = OfficialRevision.Origin.OFFICIAL;
        OfficialRevision current;
        int disasterReads;
        Map<UUID, OfficialRevision> revisions = new HashMap<>();
        List<EndedDisaster> ended = List.of();
        Runnable afterDisasterRead = () -> { };
        @Override public Optional<Source> findDisaster(UUID id) {
            disasterReads++;
            var snapshot = exists ? Optional.of(new Source(id, origin, Optional.ofNullable(current))) : Optional.<Source>empty();
            afterDisasterRead.run();
            return snapshot;
        }
        @Override public Optional<OfficialRevision> findRevision(UUID id) { return Optional.ofNullable(revisions.get(id)); }
        @Override public boolean isCurrentRevision(UUID id) { return current != null && current.revisionId().equals(id); }
        @Override public List<EndedDisaster> findEndedAtOrBefore(Instant cutoff) { return ended; }
    }

    private static class FakeGenerator implements SummaryGenerator {
        boolean configured = true;
        int calls;
        OfficialRevision lastInput;
        Function<OfficialRevision, Generated> answer = ignored -> new Generated("Supported AI summary.", "test-model", NOW.plusSeconds(1));
        @Override public boolean isConfigured() { return configured; }
        @Override public Generated generate(OfficialRevision revision) {
            calls++;
            lastInput = revision;
            return answer.apply(revision);
        }
    }

    private record Task(UUID revisionId, Instant eligibleAt) { }

    private static class FakeDispatcher implements SummaryTaskDispatcher {
        boolean fail;
        List<Task> tasks = new ArrayList<>();
        @Override public void enqueue(UUID id, Instant eligibleAt) {
            if (fail) { throw new IllegalStateException("External token=secret"); }
            tasks.add(new Task(id, eligibleAt));
        }
    }

    private static class FakeStore implements SummaryStore {
        Map<UUID, SummaryState> states = new HashMap<>();
        Map<UUID, OfficialRevision> metadata = new HashMap<>();
        Supplier<List<SummarySourceReader.EndedDisaster>> lifecycle = List::of;
        Runnable beforeDelete = () -> { };
        Instant lastEndCutoff;
        @Override public synchronized SummaryState register(OfficialRevision revision, Instant createdAt) {
            metadata.put(revision.revisionId(), revision);
            states.replaceAll((id, state) -> metadata.get(id).sourceDisasterId().equals(revision.sourceDisasterId())
                    && metadata.get(id).revisionNo() < revision.revisionNo()
                    ? new SummaryState(state.summaryId(), state.disasterId(), id, SummaryStatus.STALE,
                    state.summaryText(), state.modelName(), state.attemptCount(), state.createdAt(), state.completedAt()) : state);
            return states.computeIfAbsent(revision.revisionId(), id -> new SummaryState(UUID.randomUUID(),
                    revision.disasterId(), id, SummaryStatus.PENDING, null, null, 0, createdAt, null));
        }
        @Override public Optional<SummaryState> findByRevision(UUID id) { return Optional.ofNullable(states.get(id)); }
        @Override public boolean hasSummary(UUID id) { return states.values().stream().anyMatch(s -> s.disasterId().equals(id)); }
        @Override public synchronized Attempt attempt(UUID id, int bound, Duration delay, Instant startedAt, Supplier<Outcome> work) {
            SummaryState state = states.get(id);
            if (state == null || state.status() == SummaryStatus.COMPLETED || state.status() == SummaryStatus.STALE
                    || (state.status() == SummaryStatus.FAILED && (state.attemptCount() >= bound
                    || state.completedAt() == null || startedAt.isBefore(state.completedAt().plus(delay))))) {
                return new Attempt(Result.NOT_ELIGIBLE, state);
            }
            Outcome outcome = work.get();
            SummaryStatus status = switch (outcome.result()) {
                case COMPLETED -> SummaryStatus.COMPLETED;
                case FAILED -> SummaryStatus.FAILED;
                case STALE -> SummaryStatus.STALE;
                case NOT_ELIGIBLE -> throw new IllegalStateException();
            };
            SummaryState updated = new SummaryState(state.summaryId(), state.disasterId(), id, status,
                    outcome.summary(), outcome.modelName(), state.attemptCount() + (state.status() == SummaryStatus.FAILED ? 1 : 0),
                    state.createdAt(), outcome.completedAt());
            states.put(id, updated);
            return new Attempt(outcome.result(), updated);
        }
        @Override public synchronized int deleteForDisaster(UUID id, Instant inclusiveEndCutoff) {
            lastEndCutoff = inclusiveEndCutoff;
            beforeDelete.run();
            boolean expired = lifecycle.get().stream().anyMatch(ended -> ended.disasterId().equals(id)
                    && ended.status().isEnded() && ended.endedAt() != null
                    && !ended.endedAt().isAfter(inclusiveEndCutoff));
            if (!expired) {
                return 0;
            }
            int before = states.size();
            states.values().removeIf(s -> s.disasterId().equals(id));
            return before - states.size();
        }
    }

    private static class MutableClock extends Clock {
        Instant current;
        MutableClock(Instant current) { this.current = current; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!zone.equals(ZoneOffset.UTC)) { throw new IllegalArgumentException("The test clock uses UTC."); }
            return this;
        }
        @Override public Instant instant() { return current; }
    }
}

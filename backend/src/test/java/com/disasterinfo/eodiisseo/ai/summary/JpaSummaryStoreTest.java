package com.disasterinfo.eodiisseo.ai.summary;

import com.disasterinfo.eodiisseo.ai.summary.adapter.JpaSummaryStore;
import com.disasterinfo.eodiisseo.ai.summary.entity.AiSummary;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryStore;
import com.disasterinfo.eodiisseo.ai.summary.repository.SummaryJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Repository call-contract tests; PostgreSQL SQL/transactions still need actual DB integration. */
class JpaSummaryStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");
    private final UUID disasterId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID revisionId = UUID.randomUUID();
    private final UUID summaryId = UUID.randomUUID();

    private SummaryJpaRepository repository;
    private JpaSummaryStore store;
    private AiSummary row;

    @BeforeEach
    void setUp() {
        repository = mock(SummaryJpaRepository.class);
        store = new JpaSummaryStore(Optional.of(repository));
        row = AiSummary.pending(summaryId, disasterId, revisionId, NOW);
        when(repository.findBySourceRevisionId(revisionId)).thenReturn(Optional.of(row));
        when(repository.tryClaimWorker(revisionId)).thenReturn(true);
        when(repository.lockSourceRecord(sourceId)).thenReturn(Optional.of(sourceId));
    }

    @Test
    void absentDatasourceRepositoryFailsExplicitlyAndNeverReturnsFakeEmptyData() {
        var disconnected = new JpaSummaryStore(Optional.empty());
        assertThatThrownBy(() -> disconnected.findByRevision(revisionId))
                .isInstanceOfSatisfying(SummaryException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(SummaryException.Reason.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void revisionOwnershipMismatchStopsBeforeAnyInsertOrStateUpdate() {
        assertThatThrownBy(() -> store.register(revision(OfficialRevision.Origin.OFFICIAL), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insertPending(any(), any(), any(), any());
        verify(repository, never()).staleOlderRevisions(any(), eq(1), any());
    }

    @Test
    void operationalStoreRejectsMockSourceEvenIfItsForeignKeysWouldMatch() {
        assertThatThrownBy(() -> store.register(revision(OfficialRevision.Origin.MOCK), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).countMatchingRevision(any(), any(), any(), eq(1));
    }

    @Test
    void registrationUsesUpsertAndStalesOnlyOlderRevisionsOfTheSameSource() {
        when(repository.countMatchingRevision(revisionId, disasterId, sourceId, 1)).thenReturn(1L);
        assertThat(store.register(revision(OfficialRevision.Origin.OFFICIAL), NOW).summaryId()).isEqualTo(summaryId);
        verify(repository).insertPending(any(UUID.class), eq(disasterId), eq(revisionId), eq(NOW));
        verify(repository).staleOlderRevisions(sourceId, 1, disasterId);
        verify(repository).staleSupersededRevision(revisionId);
    }

    @Test
    void delayedRegistrationStalesItselfIfANewerRevisionWasAlreadyCommitted() {
        when(repository.countMatchingRevision(revisionId, disasterId, sourceId, 1)).thenReturn(1L);
        when(repository.staleSupersededRevision(revisionId)).thenAnswer(ignored -> {
            row.finish(SummaryStore.Outcome.stale());
            return 1;
        });
        assertThat(store.register(revision(OfficialRevision.Origin.OFFICIAL), NOW).status())
                .isEqualTo(SummaryStatus.STALE);
        var ordered = inOrder(repository);
        ordered.verify(repository).lockSourceRecord(sourceId);
        ordered.verify(repository).insertPending(any(UUID.class), eq(disasterId), eq(revisionId), eq(NOW));
        ordered.verify(repository).staleSupersededRevision(revisionId);
        ordered.verify(repository).findBySourceRevisionId(revisionId);
    }

    @Test
    void concurrentWorkerClaimIsNonBlockingAndCannotInvokeAiAgain() {
        when(repository.tryClaimWorker(revisionId)).thenReturn(false);
        AtomicInteger externalCalls = new AtomicInteger();
        var result = store.attempt(revisionId, 2, Duration.ZERO, NOW, () -> {
            externalCalls.incrementAndGet();
            return SummaryStore.Outcome.failed(NOW);
        });
        assertThat(result.result()).isEqualTo(SummaryStore.Result.NOT_ELIGIBLE);
        assertThat(externalCalls).hasValue(0);
        verify(repository, never()).findBySourceRevisionId(revisionId);
    }

    @Test
    void alreadyCompletedJobCannotInvokeAiOnDuplicateDelivery() {
        row.finish(SummaryStore.Outcome.completed("Supported text", "test-model", NOW));
        var result = store.attempt(revisionId, 2, Duration.ZERO, NOW, () -> { throw new AssertionError("AI must not run"); });
        assertThat(result.result()).isEqualTo(SummaryStore.Result.NOT_ELIGIBLE);
        verify(repository, never()).finishAttempt(any(), any(), eq(0), any(), any(), any(), eq(0), any());
    }

    @Test
    void retryLimitStopsWorkBeforeExternalCall() {
        row.finish(SummaryStore.Outcome.failed(NOW));
        var result = store.attempt(revisionId, 0, Duration.ZERO, NOW, () -> { throw new AssertionError("No retries allowed"); });
        assertThat(result.result()).isEqualTo(SummaryStore.Result.NOT_ELIGIBLE);
    }

    @Test
    void deliveryEarlierThanCreationCannotCallExternalAi() {
        var result = store.attempt(revisionId, 2, Duration.ZERO, NOW.minusNanos(1),
                () -> { throw new AssertionError("Future pending jobs must not run"); });
        assertThat(result.result()).isEqualTo(SummaryStore.Result.NOT_ELIGIBLE);
    }

    @Test
    void completedResultCannotPrecedeTheActualWorkerStart() {
        assertThatThrownBy(() -> store.attempt(revisionId, 2, Duration.ZERO, NOW.plusSeconds(10),
                () -> SummaryStore.Outcome.completed("Supported text", "test-model", NOW.plusSeconds(9))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).finishAttempt(any(), any(), eq(0), any(), any(), any(), eq(0), any());
    }

    @Test
    void initialAttemptConditionallyCompletesWithoutIncrementingRetryCount() {
        when(repository.finishAttempt(summaryId, "PENDING", 0, "COMPLETED", "Supported text", "test-model", 0, NOW))
                .thenAnswer(ignored -> {
                    row.finish(SummaryStore.Outcome.completed("Supported text", "test-model", NOW));
                    return 1;
                });
        var result = store.attempt(revisionId, 2, Duration.ZERO, NOW,
                () -> SummaryStore.Outcome.completed("Supported text", "test-model", NOW));
        assertThat(result.result()).isEqualTo(SummaryStore.Result.COMPLETED);
        assertThat(result.state().attemptCount()).isZero();
    }

    @Test
    void retryConditionMatchesPreviousFailureAndIncrementsOnlyOneTime() {
        row.finish(SummaryStore.Outcome.failed(NOW));
        when(repository.finishAttempt(summaryId, "FAILED", 0, "FAILED", null, null, 1, NOW))
                .thenAnswer(ignored -> {
                    row.beginAttempt(2);
                    row.finish(SummaryStore.Outcome.failed(NOW));
                    return 1;
                });
        var result = store.attempt(revisionId, 2, Duration.ZERO, NOW, () -> SummaryStore.Outcome.failed(NOW));
        assertThat(result.result()).isEqualTo(SummaryStore.Result.FAILED);
        assertThat(result.state().attemptCount()).isEqualTo(1);
    }

    @Test
    void sourceChangeDuringWorkWinsOverCompletionThroughConditionalUpdate() {
        when(repository.finishAttempt(summaryId, "PENDING", 0, "COMPLETED", "Old output", "test-model", 0, NOW))
                .thenReturn(0);
        var result = store.attempt(revisionId, 2, Duration.ZERO, NOW, () -> {
            row.finish(SummaryStore.Outcome.stale());
            return SummaryStore.Outcome.completed("Old output", "test-model", NOW);
        });
        assertThat(result.result()).isEqualTo(SummaryStore.Result.STALE);
        assertThat(result.state().status()).isEqualTo(SummaryStatus.STALE);
        assertThat(result.state().summaryText()).isNull();
    }

    @Test
    void failedJobCannotRetryBeforeActualFailureTimePlusConfiguredDelay() {
        row.finish(SummaryStore.Outcome.failed(NOW.plusSeconds(10)));
        var result = store.attempt(revisionId, 2, Duration.ofSeconds(30), NOW.plusSeconds(39),
                () -> { throw new AssertionError("Retry delay must be enforced in the store"); });
        assertThat(result.result()).isEqualTo(SummaryStore.Result.NOT_ELIGIBLE);
    }

    @Test
    void failedJobIsEligibleExactlyAtFailureCompletionPlusDelay() {
        row.finish(SummaryStore.Outcome.failed(NOW.plusSeconds(10)));
        Instant nextFailure = NOW.plusSeconds(41);
        when(repository.finishAttempt(summaryId, "FAILED", 0, "FAILED", null, null, 1, nextFailure))
                .thenAnswer(ignored -> {
                    row.beginAttempt(2);
                    row.finish(SummaryStore.Outcome.failed(nextFailure));
                    return 1;
                });
        var result = store.attempt(revisionId, 2, Duration.ofSeconds(30), NOW.plusSeconds(40),
                () -> SummaryStore.Outcome.failed(nextFailure));
        assertThat(result.result()).isEqualTo(SummaryStore.Result.FAILED);
        assertThat(result.state().completedAt()).isEqualTo(nextFailure);
        assertThat(result.state().attemptCount()).isEqualTo(1);
    }

    @Test
    void legacyFailureWithoutCompletionTimeCannotInventRetryEligibility() throws Exception {
        row.finish(SummaryStore.Outcome.failed(NOW));
        var field = AiSummary.class.getDeclaredField("completedAt");
        field.setAccessible(true);
        field.set(row, null);
        var result = store.attempt(revisionId, 2, Duration.ofSeconds(30), NOW.plusSeconds(500),
                () -> { throw new AssertionError("A missing failure timestamp requires recovery policy"); });
        assertThat(result.result()).isEqualTo(SummaryStore.Result.NOT_ELIGIBLE);
    }

    @Test
    void deletionOnlyTargetsOwnedSummaryRows() {
        when(repository.lockExpiredDisaster(disasterId, NOW)).thenReturn(Optional.of(disasterId));
        when(repository.deleteSummariesForDisaster(disasterId)).thenReturn(3);
        assertThat(store.deleteForDisaster(disasterId, NOW)).isEqualTo(3);
        var ordered = inOrder(repository);
        ordered.verify(repository).lockExpiredDisaster(disasterId, NOW);
        ordered.verify(repository).deleteSummariesForDisaster(disasterId);
    }

    @Test
    void changedLifecycleCannotAuthorizeDeletionFromAnEarlierExpiredSnapshot() {
        when(repository.lockExpiredDisaster(disasterId, NOW)).thenReturn(Optional.empty());
        assertThat(store.deleteForDisaster(disasterId, NOW)).isZero();
        verify(repository, never()).deleteSummariesForDisaster(any());
    }

    private OfficialRevision revision(OfficialRevision.Origin origin) {
        return new OfficialRevision(disasterId, sourceId, revisionId, 1, "HEAVY_RAIN", null,
                OfficialRevision.DisasterStatus.ONGOING, null, "Original official text", NOW, origin);
    }
}

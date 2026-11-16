package com.disasterinfo.eodiisseo.ai.summary;

import com.disasterinfo.eodiisseo.ai.summary.entity.AiSummary;
import com.disasterinfo.eodiisseo.ai.summary.dto.SummaryResponse;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryRetryPolicy;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSummaryTest {
    private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");

    @Test
    void initialAttemptIsZeroAndOnlyRetriesIncrementTheErdCount() {
        var summary = pending();
        summary.beginAttempt(1);
        summary.finish(SummaryStore.Outcome.failed(NOW));
        assertThat(summary.snapshot().attemptCount()).isZero();
        summary.beginAttempt(1);
        summary.finish(SummaryStore.Outcome.failed(NOW));
        assertThat(summary.snapshot().attemptCount()).isEqualTo(1);
        assertThat(summary.canAttempt(1)).isFalse();
        assertThatThrownBy(() -> summary.beginAttempt(1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completedAndStaleCannotBeRegeneratedByDuplicateDelivery() {
        var completed = pending();
        completed.finish(SummaryStore.Outcome.completed("Supported text", "test-model", NOW.plusSeconds(1)));
        assertThat(completed.canAttempt(10)).isFalse();
        var stale = pending();
        stale.finish(SummaryStore.Outcome.stale());
        assertThat(stale.canAttempt(10)).isFalse();
    }

    @Test
    void invalidResultDoesNotCorruptThePendingEntity() {
        var summary = pending();
        assertThatThrownBy(() -> summary.finish(SummaryStore.Outcome.completed("Supported text", "m".repeat(101), NOW)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(summary.snapshot().status()).isEqualTo(SummaryStatus.PENDING);
        assertThat(summary.snapshot().summaryText()).isNull();
    }

    @Test
    void absentOrNegativeRetryConfigurationCannotInventPolicy() {
        assertThatThrownBy(() -> new SummaryRetryPolicy.Policy(-1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryRetryPolicy.Policy(1, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryRetryPolicy.Policy(32768, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void officialRevisionDebugStringHidesOriginalContentAndLocation() {
        var source = new OfficialRevision(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "HEAVY_RAIN", "sensitive-region", OfficialRevision.DisasterStatus.ONGOING,
                null, "sensitive-original", NOW, OfficialRevision.Origin.OFFICIAL);
        assertThat(source.toString()).doesNotContain("sensitive-region", "sensitive-original");
    }

    @Test
    void failureOutcomeClearsAiPayloadButRetainsItsProcessingCompletionTime() {
        var failed = new SummaryStore.Outcome(SummaryStore.Result.FAILED, "Incomplete text", "test-model", NOW);
        assertThat(failed.summary()).isNull();
        assertThat(failed.modelName()).isNull();
        assertThat(failed.completedAt()).isEqualTo(NOW);
    }

    @Test
    void failureCompletionTimeIsRequiredAndPersistedWithoutACompletedSummary() {
        assertThatThrownBy(() -> SummaryStore.Outcome.failed(null)).isInstanceOf(IllegalArgumentException.class);
        var failed = pending();
        failed.finish(SummaryStore.Outcome.failed(NOW.plusSeconds(5)));
        assertThat(failed.snapshot().status()).isEqualTo(SummaryStatus.FAILED);
        assertThat(failed.snapshot().completedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(failed.snapshot().summaryText()).isNull();
    }

    @Test
    void completedStorageSnapshotCannotPredateItsTaskCreation() {
        assertThatThrownBy(() -> new SummaryState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                SummaryStatus.COMPLETED, "Supported text", "test-model", 0, NOW, NOW.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedStorageSnapshotMustRespectTheModelColumnLength() {
        assertThatThrownBy(() -> new SummaryState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                SummaryStatus.COMPLETED, "Supported text", "m".repeat(101), 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unicodeSpaceOnlyPayloadCannotBypassTheWorkerOutcomeBoundary() {
        assertThatThrownBy(() -> SummaryStore.Outcome.completed("\u00A0", "test-model", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SummaryStore.Outcome.completed("Supported text", "\u2007", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unicodeSpaceOnlyPayloadCannotBypassTheStorageSnapshotBoundary() {
        assertThatThrownBy(() -> new SummaryState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                SummaryStatus.COMPLETED, "\u202F", "test-model", 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                SummaryStatus.COMPLETED, "Supported text", "\u00A0", 0, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unicodeSpaceOnlySummaryCannotBecomeACompletedResponse() {
        assertThatThrownBy(() -> new SummaryResponse(UUID.randomUUID(), SummaryStatus.COMPLETED, "\u00A0", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiSummary pending() {
        return AiSummary.pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);
    }
}

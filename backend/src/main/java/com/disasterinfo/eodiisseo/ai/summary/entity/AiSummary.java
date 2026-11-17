package com.disasterinfo.eodiisseo.ai.summary.entity;

import com.disasterinfo.eodiisseo.ai.summary.SummaryState;
import com.disasterinfo.eodiisseo.ai.summary.SummaryStatus;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** ERD 4.2.1 ai_summary only. Other domain foreign keys remain UUID scalars. */
@Entity
@Table(name = "ai_summary", uniqueConstraints = @UniqueConstraint(
        name = "uq_ai_summary_source_revision", columnNames = "source_revision_id"))
public class AiSummary {
    @Id
    @Column(name = "summary_id", nullable = false)
    private UUID summaryId;
    @Column(name = "disaster_id", nullable = false)
    private UUID disasterId;
    @Column(name = "source_revision_id", nullable = false)
    private UUID sourceRevisionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private SummaryStatus status;
    @Column(name = "summary_text", columnDefinition = "text")
    private String summaryText;
    @Column(name = "model_name", length = 100)
    private String modelName;
    @Column(name = "attempt_count", nullable = false)
    private short attemptCount;
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;
    @Column(name = "completed_at", columnDefinition = "timestamptz")
    private Instant completedAt;

    protected AiSummary() { }

    public static AiSummary pending(UUID summaryId, UUID disasterId, UUID revisionId, Instant createdAt) {
        AiSummary result = new AiSummary();
        result.summaryId = Objects.requireNonNull(summaryId);
        result.disasterId = Objects.requireNonNull(disasterId);
        result.sourceRevisionId = Objects.requireNonNull(revisionId);
        result.status = SummaryStatus.PENDING;
        result.createdAt = Objects.requireNonNull(createdAt);
        return result;
    }

    public boolean canAttempt(int maxRetries) {
        if (maxRetries < 0 || maxRetries > Short.MAX_VALUE) {
            throw new IllegalArgumentException("The retry bound exceeds the ERD smallint range.");
        }
        return status == SummaryStatus.PENDING
                || (status == SummaryStatus.FAILED && attemptCount < maxRetries);
    }

    public void beginAttempt(int maxRetries) {
        if (!canAttempt(maxRetries)) {
            throw new IllegalStateException("The summary cannot be retried.");
        }
        if (status == SummaryStatus.FAILED) {
            attemptCount++;
        }
        status = SummaryStatus.PENDING;
    }

    public void finish(SummaryStore.Outcome outcome) {
        Objects.requireNonNull(outcome);
        if (status != SummaryStatus.PENDING) {
            throw new IllegalStateException("Only a pending worker attempt can finish.");
        }
        if (outcome.result() == SummaryStore.Result.COMPLETED
                && (outcome.modelName().length() > 100 || outcome.completedAt().isBefore(createdAt))) {
            throw new IllegalArgumentException("The generated result does not match the ERD contract.");
        }
        if (outcome.result() == SummaryStore.Result.FAILED && outcome.completedAt().isBefore(createdAt)) {
            throw new IllegalArgumentException("Processing completion cannot precede task creation.");
        }
        status = switch (outcome.result()) {
            case COMPLETED -> SummaryStatus.COMPLETED;
            case FAILED -> SummaryStatus.FAILED;
            case STALE -> SummaryStatus.STALE;
            case NOT_ELIGIBLE -> throw new IllegalArgumentException("A worker must provide its outcome.");
        };
        if (status == SummaryStatus.COMPLETED) {
            summaryText = outcome.summary();
            modelName = outcome.modelName();
            completedAt = outcome.completedAt();
        } else if (status == SummaryStatus.FAILED) {
            completedAt = outcome.completedAt();
        }
    }

    public SummaryState snapshot() {
        return new SummaryState(summaryId, disasterId, sourceRevisionId, status, summaryText,
                modelName, attemptCount, createdAt, completedAt);
    }
}

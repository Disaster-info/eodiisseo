package com.disasterinfo.eodiisseo.ai.summary;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable storage snapshot. attemptCount counts retries; the initial attempt is zero.
 * completedAt is processing completion, including an actual failed attempt. The response DTO
 * exposes it as generatedAt only for COMPLETED. A legacy FAILED row may have no completion time.
 */
public record SummaryState(UUID summaryId, UUID disasterId, UUID sourceRevisionId, SummaryStatus status,
                           String summaryText, String modelName, int attemptCount,
                           Instant createdAt, Instant completedAt) {
    public SummaryState {
        Objects.requireNonNull(summaryId);
        Objects.requireNonNull(disasterId);
        Objects.requireNonNull(sourceRevisionId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        if (attemptCount < 0 || attemptCount > Short.MAX_VALUE) {
            throw new IllegalArgumentException("The retry count exceeds the ERD smallint range.");
        }
        if (modelName != null && modelName.length() > 100) {
            throw new IllegalArgumentException("The model name exceeds the ERD column length.");
        }
        if (completedAt != null && completedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Processing completion cannot precede task creation.");
        }
        if (status == SummaryStatus.COMPLETED && (!SummaryText.hasContent(summaryText)
                || !SummaryText.hasContent(modelName) || completedAt == null)) {
            throw new IllegalArgumentException("A completed summary requires its generated result.");
        }
    }
}

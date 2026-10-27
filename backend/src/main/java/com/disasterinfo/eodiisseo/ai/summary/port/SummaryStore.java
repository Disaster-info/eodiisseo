package com.disasterinfo.eodiisseo.ai.summary.port;

import com.disasterinfo.eodiisseo.ai.summary.OfficialRevision;
import com.disasterinfo.eodiisseo.ai.summary.SummaryState;
import com.disasterinfo.eodiisseo.ai.summary.SummaryText;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** ERD persistence contract. Only ai_summary may be changed. */
public interface SummaryStore {
    /**
     * Atomic UQ(revision), ownership validation and serialized same-provider registration.
     * Older revisions become STALE, including an old revision registered after the newer one.
     */
    SummaryState register(OfficialRevision revision, Instant createdAt);
    Optional<SummaryState> findByRevision(UUID revisionId);
    boolean hasSummary(UUID disasterId);

    /**
     * Exclusively claim a worker without blocking source registration. Only initial PENDING or FAILED within the
     * retry bound is eligible; COMPLETED/STALE and duplicate completed attempts never call work.
     * Increment retry count only when starting another attempt after FAILED, not the first call.
     * A source change during work wins; conditional completion must never replace STALE.
     * FAILED.completedAt is the real processing finish time. Enforce retryDelay from that time
     * even for duplicate/premature queue delivery. Legacy failures without that time are ineligible.
     */
    Attempt attempt(UUID revisionId, int maxRetries, Duration retryDelay, Instant startedAt, Supplier<Outcome> work);
    /**
     * Atomically recheck and lock the official lifecycle before deleting: the disaster must
     * still be ENDED/LIFTED with a real endedAt at or before inclusiveEndCutoff. A lifecycle
     * snapshot collected before this transaction cannot authorize an unconditional deletion.
     */
    int deleteForDisaster(UUID disasterId, Instant inclusiveEndCutoff);

    record Attempt(Result result, SummaryState state) { }
    enum Result { NOT_ELIGIBLE, COMPLETED, FAILED, STALE }

    record Outcome(Result result, String summary, String modelName, Instant completedAt) {
        public Outcome {
            if (result == null || result == Result.NOT_ELIGIBLE) {
                throw new IllegalArgumentException("Worker outcome is required.");
            }
            if (result == Result.COMPLETED && (!SummaryText.hasContent(summary)
                    || !SummaryText.hasContent(modelName) || completedAt == null)) {
                throw new IllegalArgumentException("A completed outcome requires a valid generated result.");
            }
            if (result != Result.COMPLETED) {
                summary = null;
                modelName = null;
                if (result == Result.STALE) {
                    completedAt = null;
                } else if (completedAt == null) {
                    throw new IllegalArgumentException("A failure requires its real processing completion time.");
                }
            }
        }

        public static Outcome completed(String summary, String model, Instant completedAt) {
            return new Outcome(Result.COMPLETED, summary, model, completedAt);
        }
        public static Outcome failed(Instant completedAt) { return new Outcome(Result.FAILED, null, null, completedAt); }
        public static Outcome stale() { return new Outcome(Result.STALE, null, null, null); }
    }
}

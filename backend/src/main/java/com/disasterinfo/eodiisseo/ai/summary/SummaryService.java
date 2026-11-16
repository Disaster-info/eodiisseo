package com.disasterinfo.eodiisseo.ai.summary;

import com.disasterinfo.eodiisseo.ai.summary.dto.SummaryResponse;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryAuthPort;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryFailureRecorder;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryGenerator;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryRetryPolicy;
import com.disasterinfo.eodiisseo.ai.summary.port.SummarySourceReader;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryStore;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryTaskDispatcher;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * FS-AISUM-001/002. No HTTP endpoint is exposed before verified authentication is connected.
 * Registration and workers are called after the official source transaction has committed.
 */
@Service
public class SummaryService {
    public static final Duration RETENTION = Duration.ofDays(90);

    private final Optional<SummaryAuthPort> auth;
    private final Optional<SummarySourceReader> sources;
    private final Optional<SummaryStore> store;
    private final Optional<SummaryGenerator> generator;
    private final Optional<SummaryTaskDispatcher> dispatcher;
    private final Optional<SummaryRetryPolicy> retryPolicy;
    private final Optional<SummaryFailureRecorder> failures;
    private final Clock clock;

    @Autowired
    public SummaryService(Optional<SummaryAuthPort> auth, Optional<SummarySourceReader> sources,
                          Optional<SummaryStore> store, Optional<SummaryGenerator> generator,
                          Optional<SummaryTaskDispatcher> dispatcher, Optional<SummaryRetryPolicy> retryPolicy,
                          Optional<SummaryFailureRecorder> failures) {
        this(auth, sources, store, generator, dispatcher, retryPolicy, failures, Clock.systemUTC());
    }

    public SummaryService(Optional<SummaryAuthPort> auth, Optional<SummarySourceReader> sources,
                          Optional<SummaryStore> store, Optional<SummaryGenerator> generator,
                          Optional<SummaryTaskDispatcher> dispatcher, Optional<SummaryRetryPolicy> retryPolicy,
                          Optional<SummaryFailureRecorder> failures, Clock clock) {
        this.auth = Objects.requireNonNull(auth);
        this.sources = Objects.requireNonNull(sources);
        this.store = Objects.requireNonNull(store);
        this.generator = Objects.requireNonNull(generator);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.failures = Objects.requireNonNull(failures);
        this.clock = Objects.requireNonNull(clock);
    }

    /** IDs must be extracted from the verified bearer/session context, never request-supplied IDs. */
    public SummaryResponse getSummary(UUID authenticatedMemberId, UUID verifiedSessionId, UUID disasterId) {
        if (authenticatedMemberId == null || verifiedSessionId == null) {
            throw new SummaryException(SummaryException.Reason.UNAUTHENTICATED);
        }
        auth.orElseThrow(SummaryService::unavailable)
                .requireActiveSession(authenticatedMemberId, verifiedSessionId);
        if (disasterId == null) {
            throw new SummaryException(SummaryException.Reason.INVALID_DISASTER_ID);
        }
        SummarySourceReader reader = sources.orElseThrow(SummaryService::unavailable);
        SummarySourceReader.Source source = reader.findDisaster(disasterId)
                .filter(item -> item.origin() == OfficialRevision.Origin.OFFICIAL)
                .orElseThrow(() -> new SummaryException(SummaryException.Reason.NOT_FOUND));
        if (!source.disasterId().equals(disasterId)) {
            throw unavailable();
        }
        SummaryStore summaries = store.orElseThrow(SummaryService::unavailable);
        Optional<OfficialRevision> current = source.currentRevision();
        Optional<SummaryState> state = current.flatMap(revision -> summaries.findByRevision(revision.revisionId()));
        if (state.isPresent()) {
            SummaryState value = state.get();
            if (!value.disasterId().equals(disasterId)
                    || !value.sourceRevisionId().equals(current.orElseThrow().revisionId())) {
                throw unavailable();
            }
            if (!reader.isCurrentRevision(value.sourceRevisionId())) {
                return new SummaryResponse(disasterId, SummaryStatus.STALE, null, null);
            }
            return new SummaryResponse(disasterId, value.status(), value.summaryText(), value.completedAt());
        }
        // An old result is never returned as the latest content, including delayed source events.
        SummaryStatus status = summaries.hasSummary(disasterId) ? SummaryStatus.STALE : SummaryStatus.PENDING;
        return new SummaryResponse(disasterId, status, null, null);
    }

    /**
     * Non-blocking registration: this method never invokes the generator or waits for a task.
     * A durable dispatcher must return after enqueueing. AI failures cannot abort official writes.
     */
    public Submission onOfficialRevisionSaved(UUID revisionId, Instant registeredAt) {
        if (revisionId == null || registeredAt == null || sources.isEmpty() || store.isEmpty()) {
            return Submission.DEPENDENCY_UNAVAILABLE;
        }
        try {
            Optional<OfficialRevision> found = sources.get().findRevision(revisionId);
            if (found.isEmpty() || !found.get().revisionId().equals(revisionId)
                    || found.get().origin() != OfficialRevision.Origin.OFFICIAL
                    || !sources.get().isCurrentRevision(revisionId)) {
                return Submission.IGNORED;
            }
            OfficialRevision revision = found.get();
            if (!SummaryText.hasContent(revision.originalText())) {
                return Submission.SOURCE_INCOMPLETE;
            }
            SummaryState state = store.get().register(revision, registeredAt);
            if (!state.disasterId().equals(revision.disasterId()) || !state.sourceRevisionId().equals(revisionId)) {
                throw unavailable();
            }
            if (state.status() == SummaryStatus.COMPLETED || state.status() == SummaryStatus.STALE
                    || state.status() == SummaryStatus.FAILED) {
                return Submission.ALREADY_HANDLED;
            }
            if (generator.isEmpty() || dispatcher.isEmpty() || !generator.get().isConfigured()) {
                return Submission.DEPENDENCY_UNAVAILABLE;
            }
            if (retryPolicy.flatMap(SummaryRetryPolicy::configuredPolicy).isEmpty()) {
                return Submission.POLICY_UNCONFIGURED;
            }
            dispatcher.get().enqueue(revisionId, registeredAt);
            return Submission.QUEUED;
        } catch (RuntimeException failure) {
            recordFailure(SummaryFailureRecorder.Code.REGISTRATION_FAILED, null, revisionId, registeredAt);
            return Submission.DEPENDENCY_UNAVAILABLE;
        }
    }

    /** Called by the connected background worker, not by GET or official transaction threads. */
    public Processing processRevision(UUID revisionId, Instant attemptedAt) {
        if (revisionId == null || attemptedAt == null || sources.isEmpty() || store.isEmpty()
                || generator.isEmpty() || dispatcher.isEmpty()) {
            return Processing.DEPENDENCY_UNAVAILABLE;
        }
        Optional<SummaryRetryPolicy.Policy> configured;
        try {
            configured = retryPolicy.flatMap(SummaryRetryPolicy::configuredPolicy);
        } catch (RuntimeException failure) {
            return Processing.POLICY_UNCONFIGURED;
        }
        if (configured.isEmpty()) {
            return Processing.POLICY_UNCONFIGURED;
        }
        SummaryRetryPolicy.Policy policy = configured.get();
        try {
            if (attemptedAt.isAfter(clock.instant())) {
                return Processing.NOT_ELIGIBLE;
            }
            if (!generator.get().isConfigured()) {
                return Processing.DEPENDENCY_UNAVAILABLE;
            }
            SummaryStore.Attempt result = store.get().attempt(revisionId, policy.maxRetries(), policy.retryDelay(), attemptedAt,
                    () -> generate(revisionId, attemptedAt));
            if (result.result() == SummaryStore.Result.FAILED
                    && result.state().attemptCount() < policy.maxRetries()) {
                try {
                    dispatcher.get().enqueue(revisionId, result.state().completedAt().plus(policy.retryDelay()));
                } catch (RuntimeException failure) {
                    recordFailure(SummaryFailureRecorder.Code.DISPATCH_FAILED,
                            result.state().disasterId(), revisionId, attemptedAt);
                }
            }
            return switch (result.result()) {
                case NOT_ELIGIBLE -> Processing.NOT_ELIGIBLE;
                case COMPLETED -> Processing.COMPLETED;
                case FAILED -> Processing.FAILED;
                case STALE -> Processing.STALE;
            };
        } catch (RuntimeException failure) {
            recordFailure(SummaryFailureRecorder.Code.STORAGE_FAILED, null, revisionId, attemptedAt);
            return Processing.DEPENDENCY_UNAVAILABLE;
        }
    }

    private SummaryStore.Outcome generate(UUID revisionId, Instant attemptedAt) {
        UUID disasterId = null;
        try {
            Optional<OfficialRevision> found = sources.orElseThrow().findRevision(revisionId);
            if (found.isEmpty() || !found.get().revisionId().equals(revisionId)
                    || found.get().origin() != OfficialRevision.Origin.OFFICIAL
                    || !sources.get().isCurrentRevision(revisionId)) {
                return SummaryStore.Outcome.stale();
            }
            OfficialRevision revision = found.get();
            disasterId = revision.disasterId();
            if (!SummaryText.hasContent(revision.originalText())) {
                recordFailure(SummaryFailureRecorder.Code.SOURCE_INCOMPLETE, disasterId, revisionId, attemptedAt);
                return SummaryStore.Outcome.failed(clock.instant());
            }
            SummaryGenerator.Generated generated = generator.orElseThrow().generate(revision);
            if (generated == null || !SummaryText.hasContent(generated.text())
                    || !SummaryText.hasContent(generated.modelName())
                    || generated.modelName().length() > 100
                    || generated.generatedAt() == null || generated.generatedAt().isBefore(attemptedAt)) {
                recordFailure(SummaryFailureRecorder.Code.INVALID_AI_RESULT, disasterId, revisionId, attemptedAt);
                return SummaryStore.Outcome.failed(clock.instant());
            }
            if (!sources.get().isCurrentRevision(revisionId)) {
                return SummaryStore.Outcome.stale();
            }
            return SummaryStore.Outcome.completed(generated.text(), generated.modelName(), generated.generatedAt());
        } catch (RuntimeException failure) {
            // Exception messages can contain keys, source content or locations; never store them.
            recordFailure(SummaryFailureRecorder.Code.GENERATION_FAILED, disasterId, revisionId, attemptedAt);
            return SummaryStore.Outcome.failed(clock.instant());
        }
    }

    /** Delete summaries only, after the official lifecycle reader proves the real end timestamp. */
    public int purgeExpiredSummaries(Instant now) {
        Objects.requireNonNull(now, "Cleanup time is required.");
        SummarySourceReader lifecycle = sources.orElseThrow(SummaryService::unavailable);
        SummaryStore summaries = store.orElseThrow(SummaryService::unavailable);
        Instant cutoff = now.minus(RETENTION);
        int removed = 0;
        for (SummarySourceReader.EndedDisaster ended : lifecycle.findEndedAtOrBefore(cutoff)) {
            if (ended.endedAt() != null && ended.status().isEnded()
                    && !ended.endedAt().isAfter(cutoff)) {
                removed += summaries.deleteForDisaster(ended.disasterId(), cutoff);
            }
        }
        return removed;
    }

    private void recordFailure(SummaryFailureRecorder.Code code, UUID disasterId, UUID revisionId, Instant at) {
        try {
            failures.ifPresent(recorder -> recorder.record(code, disasterId, revisionId, at));
        } catch (RuntimeException ignored) {
            // Error logging failures must not escape into the official disaster path.
        }
    }

    private static SummaryException unavailable() {
        return new SummaryException(SummaryException.Reason.DEPENDENCY_UNAVAILABLE);
    }

    public enum Submission {
        QUEUED, ALREADY_HANDLED, IGNORED, SOURCE_INCOMPLETE, POLICY_UNCONFIGURED, DEPENDENCY_UNAVAILABLE
    }

    public enum Processing {
        COMPLETED, FAILED, STALE, NOT_ELIGIBLE, POLICY_UNCONFIGURED, DEPENDENCY_UNAVAILABLE
    }
}

package com.disasterinfo.eodiisseo.ai.summary.adapter;

import com.disasterinfo.eodiisseo.ai.summary.OfficialRevision;
import com.disasterinfo.eodiisseo.ai.summary.SummaryException;
import com.disasterinfo.eodiisseo.ai.summary.SummaryState;
import com.disasterinfo.eodiisseo.ai.summary.SummaryStatus;
import com.disasterinfo.eodiisseo.ai.summary.entity.AiSummary;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryStore;
import com.disasterinfo.eodiisseo.ai.summary.repository.SummaryJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Real persistence adapter, no in-memory production substitute. Optional repository keeps the
 * existing no-datasource test/application context bootable; operations fail explicitly if absent.
 * The worker uses a nonblocking advisory claim. Only the final conditional update briefly locks
 * a summary row; source registration never waits for the LLM call to release a row lock.
 */
@Repository
@Transactional
public class JpaSummaryStore implements SummaryStore {
    private final Optional<SummaryJpaRepository> repository;

    public JpaSummaryStore(Optional<SummaryJpaRepository> repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public SummaryState register(OfficialRevision revision, Instant createdAt) {
        Objects.requireNonNull(revision);
        Objects.requireNonNull(createdAt);
        if (revision.origin() != OfficialRevision.Origin.OFFICIAL) {
            throw new IllegalArgumentException("Mock revisions cannot create operational summaries.");
        }
        SummaryJpaRepository rows = rows();
        if (rows.lockSourceRecord(revision.sourceDisasterId()).isEmpty()) {
            throw new IllegalArgumentException("The official source record is unavailable.");
        }
        if (rows.countMatchingRevision(revision.revisionId(), revision.disasterId(),
                revision.sourceDisasterId(), revision.revisionNo()) != 1) {
            throw new IllegalArgumentException("Revision ownership must match the official disaster.");
        }
        rows.insertPending(UUID.randomUUID(), revision.disasterId(), revision.revisionId(), createdAt);
        rows.staleOlderRevisions(revision.sourceDisasterId(), revision.revisionNo(), revision.disasterId());
        rows.staleSupersededRevision(revision.revisionId());
        SummaryState result = rows.findBySourceRevisionId(revision.revisionId()).orElseThrow().snapshot();
        if (!result.disasterId().equals(revision.disasterId())) {
            throw new IllegalStateException("The stored revision belongs to another disaster.");
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SummaryState> findByRevision(UUID revisionId) {
        return rows().findBySourceRevisionId(revisionId).map(AiSummary::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasSummary(UUID disasterId) {
        return rows().existsByDisasterId(disasterId);
    }

    @Override
    public Attempt attempt(UUID revisionId, int maxRetries, Duration retryDelay, Instant startedAt, Supplier<Outcome> work) {
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(retryDelay);
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("A nonnegative retry delay is required.");
        }
        Objects.requireNonNull(work);
        SummaryJpaRepository rows = rows();
        if (!rows.tryClaimWorker(revisionId)) {
            return new Attempt(Result.NOT_ELIGIBLE, null);
        }
        Optional<AiSummary> found = rows.findBySourceRevisionId(revisionId);
        if (found.isEmpty()) {
            return new Attempt(Result.NOT_ELIGIBLE, null);
        }
        AiSummary summary = found.get();
        if (!summary.canAttempt(maxRetries)) {
            return new Attempt(Result.NOT_ELIGIBLE, summary.snapshot());
        }
        SummaryState previous = summary.snapshot();
        if (startedAt.isBefore(previous.createdAt())) {
            return new Attempt(Result.NOT_ELIGIBLE, previous);
        }
        if (previous.status() == SummaryStatus.FAILED
                && (previous.completedAt() == null || startedAt.isBefore(previous.completedAt().plus(retryDelay)))) {
            return new Attempt(Result.NOT_ELIGIBLE, previous);
        }
        Outcome outcome = Objects.requireNonNull(work.get());
        if (outcome.result() == Result.FAILED && outcome.completedAt().isBefore(startedAt)) {
            throw new IllegalArgumentException("Processing completion cannot precede the worker attempt.");
        }
        if (outcome.result() == Result.COMPLETED
                && (outcome.modelName().length() > 100 || outcome.completedAt().isBefore(startedAt))) {
            throw new IllegalArgumentException("The generated result does not match the ERD contract.");
        }
        int retries = previous.attemptCount() + (previous.status() == SummaryStatus.FAILED ? 1 : 0);
        String status = switch (outcome.result()) {
            case COMPLETED -> SummaryStatus.COMPLETED.name();
            case FAILED -> SummaryStatus.FAILED.name();
            case STALE -> SummaryStatus.STALE.name();
            case NOT_ELIGIBLE -> throw new IllegalArgumentException("A worker must provide its outcome.");
        };
        int updated = rows.finishAttempt(previous.summaryId(), previous.status().name(), previous.attemptCount(),
                status, outcome.summary(), outcome.modelName(), retries, outcome.completedAt());
        Optional<SummaryState> persisted = rows.findBySourceRevisionId(revisionId).map(AiSummary::snapshot);
        if (updated == 0) {
            return new Attempt(persisted.filter(state -> state.status() == SummaryStatus.STALE).isPresent()
                    ? Result.STALE : Result.NOT_ELIGIBLE, persisted.orElse(null));
        }
        return new Attempt(outcome.result(), persisted.orElseThrow());
    }

    @Override
    public int deleteForDisaster(UUID disasterId, Instant inclusiveEndCutoff) {
        Objects.requireNonNull(disasterId);
        Objects.requireNonNull(inclusiveEndCutoff);
        SummaryJpaRepository rows = rows();
        if (rows.lockExpiredDisaster(disasterId, inclusiveEndCutoff).isEmpty()) {
            return 0;
        }
        return rows.deleteSummariesForDisaster(disasterId);
    }

    private SummaryJpaRepository rows() {
        return repository.orElseThrow(() -> new SummaryException(SummaryException.Reason.DEPENDENCY_UNAVAILABLE));
    }
}

package com.disasterinfo.eodiisseo.ai.summary;

import com.disasterinfo.eodiisseo.ai.summary.adapter.JpaSummaryStore;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryStore;
import com.disasterinfo.eodiisseo.ai.summary.repository.SummaryJpaRepository;
import com.disasterinfo.eodiisseo.dashboard.PostgresAuditSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL/JPA tests; shared-domain rows are disposable read-contract fixtures only. */
class SummaryPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");
    private final UUID disasterId = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID revisionId = UUID.randomUUID();
    private PostgresAuditSupport database;

    @BeforeEach
    void setUp() throws Exception {
        database = new PostgresAuditSupport();
        database.sql("INSERT INTO disaster_type VALUES ('" + typeId + "','HEAVY_RAIN','Rain',true)");
        database.sql("INSERT INTO disaster(disaster_id,disaster_type_id,title,occurred_at,status,data_origin,updated_at)"
                + " VALUES ('" + disasterId + "','" + typeId + "','Official rain','" + NOW
                + "','ONGOING','OFFICIAL','" + NOW + "')");
        database.sql("INSERT INTO source_disaster(source_disaster_id,disaster_id,source_id,source_record_key,"
                + "original_text,collected_at,content_hash) VALUES ('" + sourceId + "','" + disasterId + "','"
                + UUID.randomUUID() + "','official-record','Immutable original 1','" + NOW + "',repeat('1',64))");
        addRevision(revisionId, 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void delayedOlderRegistrationIsStaleEvenIfTheNewerJobCommittedFirst() {
        UUID newerRevisionId = UUID.randomUUID();
        addRevision(newerRevisionId, 2);
        SummaryState newer = withStore(store -> store.register(revision(newerRevisionId, 2), NOW));
        SummaryState older = withStore(store -> store.register(revision(revisionId, 1), NOW.plusSeconds(1)));

        assertThat(newer.status()).isEqualTo(SummaryStatus.PENDING);
        assertThat(older.status()).isEqualTo(SummaryStatus.STALE);
        assertThat(withStore(store -> store.findByRevision(newerRevisionId).orElseThrow()).status())
                .isEqualTo(SummaryStatus.PENDING);
        assertThat(database.count("ai_summary")).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateRegistrationsCreateExactlyOneRevisionJob() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                await(start);
                return withStore(store -> store.register(revision(revisionId, 1), NOW));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                await(start);
                return withStore(store -> store.register(revision(revisionId, 1), NOW.plusSeconds(1)));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            SummaryState firstResult = first.get(10, TimeUnit.SECONDS);
            SummaryState secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.summaryId()).isEqualTo(secondResult.summaryId());
            assertThat(firstResult.createdAt()).isEqualTo(secondResult.createdAt());
            assertThat(database.count("ai_summary")).isEqualTo(1);
        } finally {
            start.countDown();
            stop(executor);
        }
    }

    @Test
    void concurrentWorkersUseOneNonblockingClaimAndInvokeTheGeneratorOnce() throws Exception {
        withStore(store -> store.register(revision(revisionId, 1), NOW));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger generated = new AtomicInteger();
        try {
            var winner = executor.submit(() -> withStore(store -> store.attempt(revisionId, 2, Duration.ZERO, NOW, () -> {
                generated.incrementAndGet();
                generating.countDown();
                await(release);
                return SummaryStore.Outcome.completed("Supported current summary", "audit-model", NOW.plusSeconds(1));
            })));
            assertThat(generating.await(10, TimeUnit.SECONDS)).isTrue();
            var duplicate = executor.submit(() -> withStore(store -> store.attempt(revisionId, 2, Duration.ZERO, NOW,
                    () -> { throw new AssertionError("The duplicate must not invoke generation"); })));
            assertThat(duplicate.get(10, TimeUnit.SECONDS).result()).isEqualTo(SummaryStore.Result.NOT_ELIGIBLE);
            assertThat(generated).hasValue(1);

            release.countDown();
            assertThat(winner.get(10, TimeUnit.SECONDS).result()).isEqualTo(SummaryStore.Result.COMPLETED);
            SummaryState stored = withStore(store -> store.findByRevision(revisionId).orElseThrow());
            assertThat(stored.status()).isEqualTo(SummaryStatus.COMPLETED);
            assertThat(stored.summaryText()).isEqualTo("Supported current summary");
            assertThat(stored.attemptCount()).isZero();
        } finally {
            release.countDown();
            stop(executor);
        }
    }

    @Test
    void newRevisionRegistersDuringGenerationAndWinsTheConditionalCompletionRace() throws Exception {
        withStore(store -> store.register(revision(revisionId, 1), NOW));
        UUID newerRevisionId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var oldWorker = executor.submit(() -> withStore(store -> store.attempt(revisionId, 2, Duration.ZERO, NOW, () -> {
                generating.countDown();
                await(release);
                return SummaryStore.Outcome.completed("Old result must never win", "audit-model", NOW.plusSeconds(2));
            })));
            assertThat(generating.await(10, TimeUnit.SECONDS)).isTrue();
            var newRegistration = executor.submit(() -> {
                addRevision(newerRevisionId, 2);
                return withStore(store -> store.register(revision(newerRevisionId, 2), NOW.plusSeconds(1)));
            });
            assertThat(newRegistration.get(10, TimeUnit.SECONDS).status()).isEqualTo(SummaryStatus.PENDING);
            assertThat(withStore(store -> store.findByRevision(revisionId).orElseThrow()).status())
                    .isEqualTo(SummaryStatus.STALE);

            release.countDown();
            SummaryStore.Attempt oldResult = oldWorker.get(10, TimeUnit.SECONDS);
            assertThat(oldResult.result()).isEqualTo(SummaryStore.Result.STALE);
            assertThat(oldResult.state().status()).isEqualTo(SummaryStatus.STALE);
            assertThat(oldResult.state().summaryText()).isNull();
            assertThat(withStore(store -> store.findByRevision(newerRevisionId).orElseThrow()).status())
                    .isEqualTo(SummaryStatus.PENDING);
        } finally {
            release.countDown();
            stop(executor);
        }
    }

    @Test
    void cleanupRechecksLifecycleAndDeletesOnlyAtTheRealNinetyDayCutoff() throws Exception {
        withStore(store -> store.register(revision(revisionId, 1), NOW));
        Instant cutoff = NOW.minus(SummaryService.RETENTION);
        // A scheduler's earlier ENDED snapshot cannot authorize deleting a reopened disaster.
        database.sql("UPDATE disaster SET ended_at = '" + cutoff.minusSeconds(1) + "' WHERE disaster_id = '" + disasterId + "'");
        assertThat(this.<Integer>withStore(store -> store.deleteForDisaster(disasterId, cutoff))).isZero();
        // The official end time was corrected after candidate selection: 90 days have not elapsed.
        database.sql("UPDATE disaster SET status = 'LIFTED', ended_at = '" + cutoff.plusSeconds(1)
                + "' WHERE disaster_id = '" + disasterId + "'");
        assertThat(this.<Integer>withStore(store -> store.deleteForDisaster(disasterId, cutoff))).isZero();
        assertThat(database.count("ai_summary")).isEqualTo(1);

        database.sql("UPDATE disaster SET ended_at = '" + cutoff + "' WHERE disaster_id = '" + disasterId + "'");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch checkingLifecycle = new CountDownLatch(1);
        try (Connection lifecycleChange = database.schemaConnection()) {
            lifecycleChange.setAutoCommit(false);
            try (var query = lifecycleChange.createStatement()) {
                query.executeUpdate("UPDATE disaster SET status = 'ONGOING' WHERE disaster_id = '" + disasterId + "'");
            }
            try {
                var cleanup = executor.submit(() -> this.<Integer>withStore(store -> {
                    checkingLifecycle.countDown();
                    return store.deleteForDisaster(disasterId, cutoff);
                }));
                assertThat(checkingLifecycle.await(10, TimeUnit.SECONDS)).isTrue();
                // The row lock must wait for the in-flight official lifecycle update.
                assertThatThrownBy(() -> cleanup.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                lifecycleChange.commit();
                // PostgreSQL rechecks the updated status after acquiring the row lock.
                assertThat(cleanup.get(10, TimeUnit.SECONDS)).isZero();
            } finally {
                lifecycleChange.rollback();
            }
        } finally {
            stop(executor);
        }
        assertThat(database.count("ai_summary")).isEqualTo(1);
        database.sql("UPDATE disaster SET status = 'LIFTED' WHERE disaster_id = '" + disasterId + "'");
        assertThat(this.<Integer>withStore(store -> store.deleteForDisaster(disasterId, cutoff))).isEqualTo(1);
        assertThat(database.count("ai_summary")).isZero();
        assertThat(database.count("disaster")).isEqualTo(1);
        assertThat(database.count("source_disaster_revision")).isEqualTo(1);
    }

    @Test
    void failedFinalUpdateRollsBackTheStateAndReleasesTheTransactionScopedClaim() {
        withStore(store -> store.register(revision(revisionId, 1), NOW));
        database.sql("ALTER TABLE ai_summary ADD CONSTRAINT audit_reject_completed CHECK(status <> 'COMPLETED')");
        assertThatThrownBy(() -> withStore(store -> store.attempt(revisionId, 2, Duration.ZERO, NOW,
                () -> SummaryStore.Outcome.completed("Supported current summary", "audit-model", NOW.plusSeconds(1)))))
                .isInstanceOf(RuntimeException.class);
        SummaryState unchanged = withStore(store -> store.findByRevision(revisionId).orElseThrow());
        assertThat(unchanged.status()).isEqualTo(SummaryStatus.PENDING);
        assertThat(unchanged.summaryText()).isNull();
        assertThat(unchanged.completedAt()).isNull();

        database.sql("ALTER TABLE ai_summary DROP CONSTRAINT audit_reject_completed");
        assertThat(withStore(store -> store.attempt(revisionId, 2, Duration.ZERO, NOW,
                () -> SummaryStore.Outcome.completed("Supported current summary", "audit-model", NOW.plusSeconds(1)))).result())
                .isEqualTo(SummaryStore.Result.COMPLETED);
    }

    private <T> T withStore(Function<JpaSummaryStore, T> action) {
        return database.tx(em -> action.apply(new JpaSummaryStore(Optional.of(
                database.repository(em, SummaryJpaRepository.class)))));
    }

    private void addRevision(UUID id, int number) {
        database.sql("INSERT INTO source_disaster_revision(revision_id,source_disaster_id,revision_no,original_text,"
                + "content_hash,observed_at) VALUES ('" + id + "','" + sourceId + "'," + number
                + ",'Immutable original " + number + "',repeat('" + number + "',64),'" + NOW + "')");
    }

    private OfficialRevision revision(UUID id, int number) {
        return new OfficialRevision(disasterId, sourceId, id, number, "HEAVY_RAIN", "Official region",
                OfficialRevision.DisasterStatus.ONGOING, null, "Immutable original " + number, NOW,
                OfficialRevision.Origin.OFFICIAL);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The controlled PostgreSQL race timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The controlled PostgreSQL race was interrupted", interrupted);
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("PostgreSQL race workers did not stop");
        }
    }
}

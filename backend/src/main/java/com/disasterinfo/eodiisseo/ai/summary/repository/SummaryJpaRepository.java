package com.disasterinfo.eodiisseo.ai.summary.repository;

import com.disasterinfo.eodiisseo.ai.summary.entity.AiSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL persistence. Source tables are read only, and are never mapped as duplicate entities. */
public interface SummaryJpaRepository extends JpaRepository<AiSummary, UUID> {
    Optional<AiSummary> findBySourceRevisionId(UUID sourceRevisionId);
    boolean existsByDisasterId(UUID disasterId);

    /** Serialize only short registrations, never the worker's external generation call. */
    @Query(value = "select source_disaster_id from source_disaster where source_disaster_id = :sourceId for update",
            nativeQuery = true)
    Optional<UUID> lockSourceRecord(@Param("sourceId") UUID sourceId);

    /** Recheck current lifecycle under a row lock so status/end-time changes cannot race deletion. */
    @Query(value = """
            select disaster_id from disaster
            where disaster_id = :disasterId and status in ('ENDED', 'LIFTED')
              and ended_at is not null and ended_at <= :inclusiveEndCutoff
            for update
            """, nativeQuery = true)
    Optional<UUID> lockExpiredDisaster(@Param("disasterId") UUID disasterId,
                                     @Param("inclusiveEndCutoff") Instant inclusiveEndCutoff);

    /** A nonblocking transaction-scoped worker claim avoids holding an official/source row lock. */
    @Query(value = "select pg_try_advisory_xact_lock(hashtextextended(cast(:revisionId as text), 0))",
            nativeQuery = true)
    boolean tryClaimWorker(@Param("revisionId") UUID revisionId);

    /** A newer source registration always wins over completion of an older in-flight result. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update ai_summary set status = :status, summary_text = :text,
              model_name = :model, attempt_count = :retryCount, completed_at = :completedAt
            where summary_id = :summaryId and status = :previousStatus and attempt_count = :previousRetries
            """, nativeQuery = true)
    int finishAttempt(@Param("summaryId") UUID summaryId, @Param("previousStatus") String previousStatus,
                      @Param("previousRetries") int previousRetries, @Param("status") String status,
                      @Param("text") String text, @Param("model") String model,
                      @Param("retryCount") int retryCount, @Param("completedAt") Instant completedAt);

    @Query(value = """
            select count(*) from source_disaster_revision r
            join source_disaster d on d.source_disaster_id = r.source_disaster_id
            where r.revision_id = :revisionId and d.disaster_id = :disasterId
              and d.source_disaster_id = :sourceId and r.revision_no = :revisionNo
            """, nativeQuery = true)
    long countMatchingRevision(@Param("revisionId") UUID revisionId, @Param("disasterId") UUID disasterId,
                              @Param("sourceId") UUID sourceId, @Param("revisionNo") int revisionNo);

    @Modifying
    @Query(value = """
            insert into ai_summary (summary_id, disaster_id, source_revision_id, status, attempt_count, created_at)
            values (:summaryId, :disasterId, :revisionId, 'PENDING', 0, :createdAt)
            on conflict (source_revision_id) do nothing
            """, nativeQuery = true)
    int insertPending(@Param("summaryId") UUID summaryId, @Param("disasterId") UUID disasterId,
                      @Param("revisionId") UUID revisionId, @Param("createdAt") Instant createdAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update ai_summary s set status = 'STALE'
            from source_disaster_revision r
            where s.source_revision_id = r.revision_id and r.source_disaster_id = :sourceId
              and r.revision_no < :revisionNo and s.disaster_id = :disasterId and s.status <> 'STALE'
            """, nativeQuery = true)
    int staleOlderRevisions(@Param("sourceId") UUID sourceId, @Param("revisionNo") int revisionNo,
                            @Param("disasterId") UUID disasterId);

    /** A delayed old registration must itself be STALE even when the newer job committed first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update ai_summary s set status = 'STALE'
            from source_disaster_revision r
            where s.source_revision_id = :revisionId and r.revision_id = s.source_revision_id
              and s.status <> 'STALE' and exists (
                select 1 from source_disaster_revision newer
                where newer.source_disaster_id = r.source_disaster_id and newer.revision_no > r.revision_no
              )
            """, nativeQuery = true)
    int staleSupersededRevision(@Param("revisionId") UUID revisionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from AiSummary s where s.disasterId = :disasterId")
    int deleteSummariesForDisaster(@Param("disasterId") UUID disasterId);
}

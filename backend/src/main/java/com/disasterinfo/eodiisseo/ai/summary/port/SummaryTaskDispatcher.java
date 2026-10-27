package com.disasterinfo.eodiisseo.ai.summary.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable non-inline enqueue contract. Invoke SummaryService.processRevision only in a worker,
 * at or after eligibleAt. Use source revision as the idempotency key for waiting/active tasks;
 * duplicate enqueue must not create another concurrent job. When the active worker schedules
 * its retry, persist one successor delivery at eligibleAt; do not discard it because the same
 * revision is active. Delivery may repeat after failure;
 * the store must serialize and suppress duplicate completed attempts.
 * Recover pending tasks after restart. No default executor is added without the shared lifecycle.
 * Recover FAILED retry scheduling only from the stored processing-completion time and approved
 * policy. A repeated official-source event is not a retry trigger and cannot bypass eligibleAt.
 */
public interface SummaryTaskDispatcher {
    void enqueue(UUID revisionId, Instant eligibleAt);
}

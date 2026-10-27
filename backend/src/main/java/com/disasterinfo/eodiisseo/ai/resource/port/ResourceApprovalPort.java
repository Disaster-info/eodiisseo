package com.disasterinfo.eodiisseo.ai.resource.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit review evidence, supplied by an approved publication manifest or future review system.
 * Hostname alone never grants approval. Review must verify original content, agency, usage licence,
 * public AI transmission, prompt-injection safety and a finite recheck deadline.
 * No default approval or production freshness duration is invented by this module.
 * Implementations expose only the currently approved publication and revoke superseded resource IDs;
 * checked_at and free-form version_label must never be used to guess the current source version.
 */
public interface ResourceApprovalPort {
    Optional<Approval> findApproval(UUID resourceId);

    record Approval(UUID resourceId, String revisionFingerprint, Instant reviewedAt, Instant validUntil,
                    boolean approvedPublicAiEvidence) { }
}

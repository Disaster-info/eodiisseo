package com.disasterinfo.eodiisseo.ai.summary.port;

import com.disasterinfo.eodiisseo.ai.summary.OfficialRevision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only disaster/collection contract. findRevision returns the exact immutable UUID version
 * and isCurrentRevision checks the latest revision of that version's same provider record.
 * Representative provider selection for a disaster belongs to disaster, never to AI.
 */
public interface SummarySourceReader {
    Optional<Source> findDisaster(UUID disasterId);
    Optional<OfficialRevision> findRevision(UUID revisionId);
    boolean isCurrentRevision(UUID revisionId);
    List<EndedDisaster> findEndedAtOrBefore(Instant inclusiveCutoff);

    record Source(UUID disasterId, OfficialRevision.Origin origin, Optional<OfficialRevision> currentRevision) {
        public Source {
            Objects.requireNonNull(disasterId);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(currentRevision);
            if (currentRevision.isPresent() && (!currentRevision.get().disasterId().equals(disasterId)
                    || currentRevision.get().origin() != origin)) {
                throw new IllegalArgumentException("Revision ownership and data origin must match the disaster.");
            }
        }
    }

    record EndedDisaster(UUID disasterId, OfficialRevision.DisasterStatus status, Instant endedAt) {
        public EndedDisaster {
            Objects.requireNonNull(disasterId);
            Objects.requireNonNull(status);
        }
    }
}

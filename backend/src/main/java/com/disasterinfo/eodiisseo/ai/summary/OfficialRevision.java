package com.disasterinfo.eodiisseo.ai.summary;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One immutable official provider revision. Provider texts must never be merged. */
public record OfficialRevision(UUID disasterId, UUID sourceDisasterId, UUID revisionId, int revisionNo,
                               String disasterType, String region, DisasterStatus disasterStatus,
                               String officialSeverity, String originalText, Instant updatedAt, Origin origin) {
    public OfficialRevision {
        Objects.requireNonNull(disasterId);
        Objects.requireNonNull(sourceDisasterId);
        Objects.requireNonNull(revisionId);
        Objects.requireNonNull(disasterStatus);
        Objects.requireNonNull(origin);
        if (revisionNo < 1) {
            throw new IllegalArgumentException("The official revision number must be positive.");
        }
    }

    public enum Origin { OFFICIAL, MOCK }

    @Override
    public String toString() {
        return "OfficialRevision[disasterId=" + disasterId + ", revisionId=" + revisionId
                + ", revisionNo=" + revisionNo + ", originalText=<redacted>, region=<redacted>]";
    }

    public enum DisasterStatus {
        OCCURRED, ONGOING, ENDED, LIFTED;

        public boolean isEnded() {
            return this == ENDED || this == LIFTED;
        }
    }
}

package com.disasterinfo.eodiisseo.dashboard.port;

import com.disasterinfo.eodiisseo.dashboard.DashboardQuery.Criteria;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.MajorDisaster;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Boundary to normalized disaster queries and existing regional/GIS comparison. */
public interface DashboardDisasterReader {

    /**
     * Return distinct national candidates, not only disasters in the selected region.
     * Determine relevance using the query basis, usable current location and the member's
     * non-deleted interest areas. No basis still means a national lookup, not stored-location weather.
     * A missing/unverifiable region is UNKNOWN, never RELATED. Reuse the domain's occurrence
     * comparison and impact selection: OFFICIAL IMPACT first, SOURCE_ADDRESS IMPACT only when
     * none exists, no OCCURRENCE promotion, and existing GIS boundary handling.
     * Preserve upstream order; dashboard tie-breaking and maximum count are not specified.
     * Query failures must throw, rather than return an empty list indicating no disasters.
     */
    List<Candidate> findCandidates(UUID authenticatedMemberId, Criteria criteria, BaseLocation baseLocation);

    record Candidate(MajorDisaster disaster, DataOrigin dataOrigin, Relevance relevance) {
        public Candidate {
            Objects.requireNonNull(disaster, "disaster");
            Objects.requireNonNull(dataOrigin, "dataOrigin");
            Objects.requireNonNull(relevance, "relevance");
        }
    }

    enum DataOrigin {
        OFFICIAL,
        MOCK
    }

    enum Relevance {
        RELATED,
        UNRELATED,
        UNKNOWN
    }
}

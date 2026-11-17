package com.disasterinfo.eodiisseo.dashboard.port;

import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.MajorDisaster;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterDetailResponse;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterListQuery.Criteria;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.DataOrigin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only normalized disaster boundary, separate from the personalized major-disaster query. */
public interface DashboardDisasterQueryReader {
    /** Query the disaster_type catalog; never restrict types to sample values in the API document. */
    boolean existsType(String typeCode);

    /**
     * Include retained ENDED/LIFTED records when requested. Preserve domain order, return distinct
     * IDs and never synthesize severity, regions or timestamps. A default query must be held until
     * its state/order/pagination policy is agreed. Latest collection failure representation is
     * also a pending API contract; report false rather than silently presenting it as latest.
     */
    ListSnapshot findDisasters(Criteria criteria);

    /**
     * Supply the agreed single official source/content and dataStatus projection. Source selection,
     * multi-source freshness and dataStatus values require domain agreement; throw POLICY_UNRESOLVED
     * if not available. Other providers' official originals must never be merged here.
     */
    Optional<DetailSnapshot> findDisaster(UUID disasterId);

    record Entry(MajorDisaster disaster, DataOrigin origin) {
        public Entry {
            Objects.requireNonNull(disaster, "disaster");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /** Explicit readiness metadata is internal and never serialized as extra API fields. */
    record ListSnapshot(List<Entry> entries, boolean defaultPolicyResolved, boolean freshnessRepresentable) {
        public ListSnapshot {
            entries = List.copyOf(entries);
        }
    }

    record DetailSnapshot(DisasterDetailResponse response, DataOrigin origin) {
        public DetailSnapshot {
            Objects.requireNonNull(response, "response");
            Objects.requireNonNull(origin, "origin");
        }
    }
}

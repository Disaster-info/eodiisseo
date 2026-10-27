package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.dashboard.dto.DisasterDetailResponse;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterListQuery;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterListQuery.Criteria;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterListResponse;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterQueryReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterQueryReader.DetailSnapshot;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterQueryReader.ListSnapshot;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.DataOrigin;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** API-DASH-002/003 orchestration; HTTP awaits real authentication and shared error handling. */
@Service
public class DashboardDisasterQueryService {
    private final Optional<DashboardDisasterQueryReader> reader;
    private final Optional<DashboardSessionVerifier> sessionVerifier;

    public DashboardDisasterQueryService(Optional<DashboardDisasterQueryReader> reader,
                                        Optional<DashboardSessionVerifier> sessionVerifier) {
        this.reader = Objects.requireNonNull(reader);
        this.sessionVerifier = Objects.requireNonNull(sessionVerifier);
    }

    public DisasterListResponse getDisasters(UUID memberId, UUID sessionId, DisasterListQuery query) {
        DashboardAccess.requireSession(sessionVerifier, memberId, sessionId);
        if (query == null) {
            throw failure(DashboardException.Reason.INVALID_QUERY, "Disaster list query is required.");
        }
        Criteria criteria = query.validate();
        DashboardDisasterQueryReader queries = requireReader();
        if (criteria.type() != null && !queries.existsType(criteria.type())) {
            throw failure(DashboardException.Reason.INVALID_QUERY, "Disaster type is not registered.");
        }
        ListSnapshot snapshot = queries.findDisasters(criteria);
        if (snapshot == null) {
            throw failure(DashboardException.Reason.DEPENDENCY_UNAVAILABLE, "Disaster list result is unavailable.");
        }
        if (!criteria.hasFilters() && !snapshot.defaultPolicyResolved()) {
            throw failure(DashboardException.Reason.POLICY_UNRESOLVED, "Default disaster list policy is not agreed.");
        }
        if (!snapshot.freshnessRepresentable()) {
            throw failure(DashboardException.Reason.POLICY_UNRESOLVED, "Disaster list freshness contract is not agreed.");
        }
        HashSet<UUID> ids = new HashSet<>();
        var disasters = snapshot.entries().stream()
                .filter(entry -> entry.origin() == DataOrigin.OFFICIAL)
                .map(DashboardDisasterQueryReader.Entry::disaster)
                .filter(disaster -> criteria.type() == null || criteria.type().equals(disaster.type()))
                .filter(disaster -> criteria.status() == null || criteria.status() == disaster.status())
                .peek(disaster -> {
                    if (!ids.add(disaster.disasterId())) {
                        throw failure(DashboardException.Reason.DEPENDENCY_UNAVAILABLE,
                                "Disaster query returned duplicate identifiers.");
                    }
                })
                .toList();
        return new DisasterListResponse(disasters);
    }

    public DisasterDetailResponse getDisaster(UUID memberId, UUID sessionId, UUID disasterId) {
        DashboardAccess.requireSession(sessionVerifier, memberId, sessionId);
        if (disasterId == null) {
            // The wire status of malformed UUIDs is unresolved in API-DASH-003; no HTTP mapping is invented.
            throw failure(DashboardException.Reason.INVALID_QUERY, "A disaster UUID is required.");
        }
        DetailSnapshot snapshot = requireReader().findDisaster(disasterId)
                .orElseThrow(() -> failure(DashboardException.Reason.NOT_FOUND, "Disaster was not found."));
        if (snapshot.origin() != DataOrigin.OFFICIAL) {
            throw failure(DashboardException.Reason.NOT_FOUND, "Disaster was not found.");
        }
        DisasterDetailResponse response = snapshot.response();
        if (!response.disasterId().equals(disasterId)) {
            throw failure(DashboardException.Reason.DEPENDENCY_UNAVAILABLE, "Disaster query returned a different identifier.");
        }
        if (response.dataStatus() == null || response.dataStatus().codePoints().allMatch(
                codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))) {
            throw failure(DashboardException.Reason.POLICY_UNRESOLVED, "Disaster freshness contract is not agreed.");
        }
        return response;
    }

    private DashboardDisasterQueryReader requireReader() {
        return reader.orElseThrow(() -> failure(DashboardException.Reason.DEPENDENCY_UNAVAILABLE,
                "Disaster query adapter is not connected."));
    }

    private static DashboardException failure(DashboardException.Reason reason, String message) {
        return new DashboardException(reason, message);
    }
}

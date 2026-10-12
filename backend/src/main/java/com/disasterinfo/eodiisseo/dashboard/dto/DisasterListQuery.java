package com.disasterinfo.eodiisseo.dashboard.dto;

import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.DisasterStatus;

/** Only API-DASH-002's specified filters; pagination and default state policy are not invented. */
public record DisasterListQuery(String type, String status) {
    public Criteria validate() {
        if (type != null && type.isBlank()) {
            throw invalid("Disaster type must be a registered type code.");
        }
        DisasterStatus parsedStatus = null;
        if (status != null) {
            try {
                parsedStatus = DisasterStatus.valueOf(status);
            } catch (IllegalArgumentException exception) {
                throw invalid("Disaster status is invalid.");
            }
        }
        return new Criteria(type, parsedStatus);
    }

    private static DashboardException invalid(String message) {
        return new DashboardException(DashboardException.Reason.INVALID_QUERY, message);
    }

    public record Criteria(String type, DisasterStatus status) {
        public boolean hasFilters() {
            return type != null || status != null;
        }
    }
}

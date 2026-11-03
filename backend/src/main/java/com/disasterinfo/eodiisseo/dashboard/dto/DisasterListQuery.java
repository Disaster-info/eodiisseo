package com.disasterinfo.eodiisseo.dashboard.dto;

import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.DisasterStatus;

/** Existing API filters and development paging inputs; the response DTO remains unchanged. */
public record DisasterListQuery(String type, String status, int page, int size) {
    public DisasterListQuery(String type,String status) { this(type,status,0,20); }
    public Criteria validate() {
        if(page<0 || size<1 || size>100) throw invalid("Invalid pagination.");
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
        return new Criteria(type, parsedStatus, page, size);
    }

    private static DashboardException invalid(String message) {
        return new DashboardException(DashboardException.Reason.INVALID_QUERY, message);
    }

    public record Criteria(String type, DisasterStatus status, int page, int size) {
        public Criteria(String type,DisasterStatus status) { this(type,status,0,20); }
        public boolean hasFilters() {
            return type != null || status != null;
        }
    }
}

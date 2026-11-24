package com.disasterinfo.eodiisseo.dashboard.dto;

import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.MajorDisaster;

import java.util.List;

/** API-DASH-002 item fields match DASH-001; all four states are valid here. */
public record DisasterListResponse(List<MajorDisaster> disasters,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String dataStatus) {
    public DisasterListResponse(List<MajorDisaster> disasters) { this(disasters, null); }
    public DisasterListResponse {
        disasters = List.copyOf(disasters);
    }
}

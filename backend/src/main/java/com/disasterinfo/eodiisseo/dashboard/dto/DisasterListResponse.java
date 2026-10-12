package com.disasterinfo.eodiisseo.dashboard.dto;

import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.MajorDisaster;

import java.util.List;

/** API-DASH-002 item fields match DASH-001; all four states are valid here. */
public record DisasterListResponse(List<MajorDisaster> disasters) {
    public DisasterListResponse {
        disasters = List.copyOf(disasters);
    }
}

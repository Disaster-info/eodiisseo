package com.disasterinfo.eodiisseo.dashboard.dto;

import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.DisasterStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact API-DASH-003 shape. The source adapter owns representative content and freshness. */
public record DisasterDetailResponse(
        UUID disasterId,
        String type,
        String title,
        String region,
        Instant occurredAt,
        String severity,
        DisasterStatus status,
        String officialContent,
        Source source,
        Instant updatedAt,
        String dataStatus
) {
    public DisasterDetailResponse {
        Objects.requireNonNull(disasterId, "disasterId");
        Objects.requireNonNull(status, "status");
    }

    public record Source(String organization) {
    }
}

package com.disasterinfo.eodiisseo.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** API 5.2.1 success response; domain metadata is not exposed in this DTO. */
public record DashboardResponse(
        BaseLocation baseLocation,
        Weather weather,
        WeatherStatus weatherStatus,
        List<MajorDisaster> majorDisasters
) {
    public DashboardResponse {
        Objects.requireNonNull(weatherStatus, "weatherStatus");
        majorDisasters = List.copyOf(majorDisasters);
    }

    public record BaseLocation(String name, double latitude, double longitude) {
    }

    /** Values, observation timestamps and stale are supplied by the weather domain. */
    public record Weather(
            BigDecimal temperature,
            String weatherCode,
            String weatherText,
            Instant observedAt,
            Instant collectedAt,
            String conditionBasis,
            Instant skyValidAt,
            boolean stale,
            String guidance
    ) {
    }

    /** Region and severity retain domain values; absent official severity remains null. */
    public record MajorDisaster(
            UUID disasterId,
            String type,
            String title,
            String region,
            DisasterStatus status,
            String severity,
            Instant occurredAt,
            Instant updatedAt
    ) {
        public MajorDisaster {
            Objects.requireNonNull(disasterId, "disasterId");
            Objects.requireNonNull(status, "status");
        }
    }

    public enum WeatherStatus {
        AVAILABLE,
        STALE,
        UNAVAILABLE,
        NOT_REQUESTED
    }

    public enum DisasterStatus {
        OCCURRED,
        ONGOING,
        ENDED,
        LIFTED
    }
}

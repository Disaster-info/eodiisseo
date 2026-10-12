package com.disasterinfo.eodiisseo.dashboard;

import java.util.UUID;

/** Raw query parameters of API-DASH-001; no member identity is accepted here. */
public record DashboardQuery(Double latitude, Double longitude, String interestAreaId) {

    public Criteria validate() {
        boolean hasLatitude = latitude != null;
        boolean hasLongitude = longitude != null;
        boolean hasInterestArea = interestAreaId != null;

        if (hasLatitude != hasLongitude) {
            throw invalid("Both latitude and longitude are required.");
        }
        if (hasLatitude && hasInterestArea) {
            throw invalid("Coordinates and interestAreaId cannot be combined.");
        }
        if (hasLatitude) {
            if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                    || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                throw invalid("Coordinates are outside the valid range.");
            }
            return new Criteria(Basis.CURRENT_LOCATION, latitude, longitude, null);
        }
        if (hasInterestArea) {
            UUID parsedId;
            try {
                parsedId = UUID.fromString(interestAreaId);
            } catch (IllegalArgumentException exception) {
                throw invalid("interestAreaId must be a UUID string.");
            }
            // UUID.fromString also accepts shortened groups; the API requires a UUID string.
            if (!parsedId.toString().equalsIgnoreCase(interestAreaId)) {
                throw invalid("interestAreaId must be a UUID string.");
            }
            return new Criteria(Basis.INTEREST_AREA, null, null, parsedId);
        }
        return new Criteria(Basis.NATIONWIDE, null, null, null);
    }

    private static DashboardException invalid(String message) {
        return new DashboardException(DashboardException.Reason.INVALID_QUERY, message);
    }

    public enum Basis {
        NATIONWIDE,
        CURRENT_LOCATION,
        INTEREST_AREA
    }

    /** Validated lookup criteria, passed to domain adapters without storing GPS data. */
    public record Criteria(Basis basis, Double latitude, Double longitude, UUID interestAreaId) {
    }
}

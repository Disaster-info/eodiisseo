package com.disasterinfo.eodiisseo.dashboard.port;

import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;

import java.util.UUID;

/** Read-only boundary to existing area/location services, not a GIS implementation. */
public interface DashboardLocationReader {

    /** Resolve a display region while preserving the requested coordinates; never save GPS. */
    BaseLocation resolveCoordinates(double latitude, double longitude);

    /**
     * Return the selected, non-deleted member-owned interest area's name and admin-area center point.
     * Verify ownership on every call; report FORBIDDEN/NOT_FOUND via DashboardException.
     * Missing names/center points must follow the agreed domain policy, not invented defaults.
     */
    BaseLocation resolveInterestArea(UUID authenticatedMemberId, UUID interestAreaId);
}

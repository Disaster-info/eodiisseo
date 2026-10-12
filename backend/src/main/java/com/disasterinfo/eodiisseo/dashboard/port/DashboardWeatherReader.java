package com.disasterinfo.eodiisseo.dashboard.port;

import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.Weather;

/** Boundary to the weather service's completed result, including its fallback/stale decision. */
public interface DashboardWeatherReader {

    /**
     * Return actual weather or null/throw on unavailability. The weather domain owns grid
     * conversion, external calls, cache (600 seconds), stale (7200 seconds) and failure
     * fallback (under 10800 seconds). Dashboard never computes or refreshes those policies.
     */
    Weather findWeather(BaseLocation baseLocation);
}

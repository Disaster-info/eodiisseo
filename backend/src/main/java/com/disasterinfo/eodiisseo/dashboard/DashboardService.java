package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.dashboard.DashboardQuery.Criteria;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.MajorDisaster;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.Weather;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.WeatherStatus;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.Candidate;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.DataOrigin;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.Relevance;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardLocationReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardWeatherReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * First-stage API-DASH-001 orchestration. The future HTTP adapter must supply the member ID
 * from existing verified authentication (ACTIVE member and valid session), never query/header IDs.
 */
@Service
public class DashboardService {

    private final Optional<DashboardLocationReader> locationReader;
    private final Optional<DashboardDisasterReader> disasterReader;
    private final Optional<DashboardWeatherReader> weatherReader;
    private final Optional<DashboardSessionVerifier> sessionVerifier;

    /** Optional adapters allow the application to start before actual domain queries exist. */
    public DashboardService(
            Optional<DashboardLocationReader> locationReader,
            Optional<DashboardDisasterReader> disasterReader,
            Optional<DashboardWeatherReader> weatherReader
    ) {
        this(locationReader, disasterReader, weatherReader, Optional.empty());
    }

    @Autowired
    public DashboardService(
            Optional<DashboardLocationReader> locationReader,
            Optional<DashboardDisasterReader> disasterReader,
            Optional<DashboardWeatherReader> weatherReader,
            Optional<DashboardSessionVerifier> sessionVerifier
    ) {
        this.locationReader = Objects.requireNonNull(locationReader);
        this.disasterReader = Objects.requireNonNull(disasterReader);
        this.weatherReader = Objects.requireNonNull(weatherReader);
        this.sessionVerifier = Objects.requireNonNull(sessionVerifier);
    }

    /** Public entry for a future protected HTTP adapter; unavailable authentication fails closed. */
    public DashboardResponse getDashboard(UUID memberId, UUID serviceSessionId, DashboardQuery query) {
        DashboardAccess.requireSession(sessionVerifier, memberId, serviceSessionId);
        return getDashboard(memberId, query);
    }

    /** Package-local orchestration used after authentication and by the existing unit tests. */
    DashboardResponse getDashboard(UUID authenticatedMemberId, DashboardQuery query) {
        if (authenticatedMemberId == null) {
            throw new DashboardException(DashboardException.Reason.UNAUTHENTICATED,
                    "An authenticated member is required.");
        }
        if (query == null) {
            throw new DashboardException(DashboardException.Reason.INVALID_QUERY,
                    "Dashboard query is required.");
        }
        Criteria criteria = query.validate();
        DashboardDisasterReader disasters = disasterReader.orElseThrow(
                () -> unavailable("Disaster query adapter is not connected."));
        BaseLocation baseLocation = resolveLocation(authenticatedMemberId, criteria);

        // Query disasters outside weather failure handling; failures must never look like no disasters.
        List<MajorDisaster> majorDisasters = disasters
                .findCandidates(authenticatedMemberId, criteria, baseLocation).stream()
                .filter(candidate -> candidate.dataOrigin() == DataOrigin.OFFICIAL)
                .filter(candidate -> switch (candidate.disaster().status()) {
                    case OCCURRED, ONGOING -> true;
                    case ENDED, LIFTED -> false;
                })
                // Ordered streams keep the upstream order within each priority group.
                .sorted(Comparator.comparing(candidate -> candidate.relevance() != Relevance.RELATED))
                .map(Candidate::disaster)
                .toList();

        if (criteria.basis() == DashboardQuery.Basis.NATIONWIDE) {
            return new DashboardResponse(null, null, WeatherStatus.NOT_REQUESTED, majorDisasters);
        }
        Weather weather = readWeather(baseLocation);
        WeatherStatus weatherStatus = weather == null ? WeatherStatus.UNAVAILABLE
                : weather.stale() ? WeatherStatus.STALE : WeatherStatus.AVAILABLE;
        return new DashboardResponse(baseLocation, weather, weatherStatus, majorDisasters);
    }

    private BaseLocation resolveLocation(UUID authenticatedMemberId, Criteria criteria) {
        if (criteria.basis() == DashboardQuery.Basis.NATIONWIDE) {
            return null;
        }
        DashboardLocationReader locations = locationReader.orElseThrow(
                () -> unavailable("Location query adapter is not connected."));
        BaseLocation resolved = switch (criteria.basis()) {
            case CURRENT_LOCATION -> locations.resolveCoordinates(criteria.latitude(), criteria.longitude());
            case INTEREST_AREA -> locations.resolveInterestArea(authenticatedMemberId, criteria.interestAreaId());
            case NATIONWIDE -> throw new IllegalStateException("Nationwide queries have no base location.");
        };
        if (resolved == null) {
            throw unavailable("Location query adapter did not resolve a base location.");
        }
        if (!Double.isFinite(resolved.latitude()) || resolved.latitude() < -90 || resolved.latitude() > 90
                || !Double.isFinite(resolved.longitude()) || resolved.longitude() < -180 || resolved.longitude() > 180) {
            throw unavailable("Location query adapter returned invalid coordinates.");
        }
        if (criteria.basis() == DashboardQuery.Basis.CURRENT_LOCATION
                && (resolved.latitude() != criteria.latitude() || resolved.longitude() != criteria.longitude())) {
            throw unavailable("Location query adapter changed the requested coordinates.");
        }
        return resolved;
    }

    private Weather readWeather(BaseLocation baseLocation) {
        try {
            return weatherReader.map(reader -> reader.findWeather(baseLocation)).orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static DashboardException unavailable(String message) {
        return new DashboardException(DashboardException.Reason.DEPENDENCY_UNAVAILABLE, message);
    }
}

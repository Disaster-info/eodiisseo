package com.disasterinfo.eodiisseo.dashboard.adapter;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.Weather;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardWeatherReader;
import com.disasterinfo.eodiisseo.weather.service.WeatherService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Uses the weather service's cache, provider fallback and freshness decisions unchanged. */
@Component
@Profile("!test")
public class WeatherServiceDashboardReader implements DashboardWeatherReader {
    private final WeatherService weather;

    public WeatherServiceDashboardReader(WeatherService weather) {
        this.weather = Objects.requireNonNull(weather);
    }

    @Override
    public Weather findWeather(BaseLocation location) {
        throw new DashboardException(DashboardException.Reason.UNAUTHENTICATED,
                "An authenticated member is required for the weather service adapter.");
    }

    @Override
    public Weather findWeather(UUID authenticatedMemberId, BaseLocation location) {
        if (authenticatedMemberId == null) {
            return findWeather(location);
        }
        if (location == null || !Double.isFinite(location.latitude()) || !Double.isFinite(location.longitude())
                || location.latitude() < -90 || location.latitude() > 90
                || location.longitude() < -180 || location.longitude() > 180) {
            throw unavailable();
        }
        // Interest-area ownership and coordinate resolution belong to DashboardLocationReader.
        // Only its resolved coordinates cross this boundary; no location is stored or shared here.
        Map<String, Object> result;
        try {
            result = weather.current(authenticatedMemberId, location.latitude(), location.longitude(), null);
        } catch (ApiProblem failure) {
            throw switch (failure.status()) {
                case 401 -> rejected(DashboardException.Reason.UNAUTHENTICATED);
                case 403 -> rejected(DashboardException.Reason.FORBIDDEN);
                default -> unavailable();
            };
        } catch (AuthenticationException failure) {
            throw rejected(DashboardException.Reason.UNAUTHENTICATED);
        } catch (AccessDeniedException failure) {
            throw rejected(DashboardException.Reason.FORBIDDEN);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (result == null) {
            throw unavailable();
        }
        requireMatchingLocation(result.get("location"), location);
        return new Weather(value(result, "temperature", BigDecimal.class, true),
                value(result, "weatherCode", String.class, false),
                value(result, "weatherText", String.class, false),
                value(result, "observedAt", Instant.class, false),
                value(result, "collectedAt", Instant.class, false),
                value(result, "conditionBasis", String.class, false),
                value(result, "skyValidAt", Instant.class, true),
                value(result, "stale", Boolean.class, false),
                value(result, "guidance", String.class, true));
    }

    private static void requireMatchingLocation(Object supplied, BaseLocation requested) {
        if (!(supplied instanceof Map<?, ?> location)
                || !(location.get("latitude") instanceof Number latitude)
                || !(location.get("longitude") instanceof Number longitude)
                || !Double.isFinite(latitude.doubleValue()) || !Double.isFinite(longitude.doubleValue())
                || latitude.doubleValue() != requested.latitude() || longitude.doubleValue() != requested.longitude()) {
            throw unavailable();
        }
    }

    private static <T> T value(Map<String, Object> result, String key, Class<T> type, boolean nullable) {
        if (!result.containsKey(key)) {
            throw unavailable();
        }
        Object value = result.get(key);
        if (value == null && nullable) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw unavailable();
        }
        return type.cast(value);
    }

    private static DashboardException unavailable() {
        return new DashboardException(DashboardException.Reason.DEPENDENCY_UNAVAILABLE,
                "The weather service did not supply a usable observation.");
    }

    private static DashboardException rejected(DashboardException.Reason reason) {
        return new DashboardException(reason, "The weather service rejected the authenticated request.");
    }
}

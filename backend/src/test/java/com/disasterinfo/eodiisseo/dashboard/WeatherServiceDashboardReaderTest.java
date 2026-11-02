package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.dashboard.adapter.WeatherServiceDashboardReader;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.DisasterStatus;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.MajorDisaster;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.WeatherStatus;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.Candidate;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.DataOrigin;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.Relevance;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardLocationReader;
import com.disasterinfo.eodiisseo.weather.client.WeatherProvider;
import com.disasterinfo.eodiisseo.weather.repository.WeatherRepository;
import com.disasterinfo.eodiisseo.weather.service.WeatherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeatherServiceDashboardReaderTest {
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");
    private static final BaseLocation SEOUL = new BaseLocation("서울", 37.5665, 126.9780);
    private static final MajorDisaster TEST_DISASTER = new MajorDisaster(UUID.randomUUID(), "TEST_ONLY",
            "테스트 재난", "테스트 지역", DisasterStatus.ONGOING, null, NOW, NOW);

    @Test
    void mapsRealWeatherServicesCachedObservationWithoutRefreshingOrRewritingValues() {
        WeatherRepository repository = mock(WeatherRepository.class);
        WeatherProvider provider = mock(WeatherProvider.class);
        UUID source = UUID.randomUUID();
        when(repository.tableExists("data_source")).thenReturn(true);
        when(repository.findActiveWeatherSource("WEATHER_API")).thenReturn(Map.of("source_id", source));
        Map<String, Object> observation = new HashMap<>();
        observation.put("temperature_c", new BigDecimal("19.20"));
        observation.put("condition_code", "UNKNOWN");
        observation.put("condition_basis", "UNKNOWN");
        observation.put("observed_at", NOW.minusSeconds(300));
        observation.put("collected_at", NOW.minusSeconds(10));
        observation.put("sky_valid_at", null);
        when(repository.findLatestObservation(source, 60, 127)).thenReturn(observation);
        WeatherService service = new WeatherService(repository, provider, Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(), 600, 7200, 10800, "WEATHER_API");

        var response = new WeatherServiceDashboardReader(service).findWeather(MEMBER, SEOUL);

        assertThat(response.temperature()).isEqualTo(new BigDecimal("19.20"));
        assertThat(response.weatherCode()).isEqualTo("UNKNOWN");
        assertThat(response.weatherText()).isEqualTo("상태 미확인");
        assertThat(response.observedAt()).isEqualTo(NOW.minusSeconds(300));
        assertThat(response.collectedAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(response.conditionBasis()).isEqualTo("UNKNOWN");
        assertThat(response.skyValidAt()).isNull();
        assertThat(response.stale()).isFalse();
        assertThat(response.guidance()).isEqualTo("최신 기상청 안내를 확인하세요.");
        verifyNoInteractions(provider);
        verify(repository, never()).findInterestArea(any());
    }

    @Test
    void publicDashboardEntryCarriesAuthenticatedMemberAndPreservesWeatherStaleDecision() {
        WeatherService service = mock(WeatherService.class);
        when(service.current(MEMBER, SEOUL.latitude(), SEOUL.longitude(), null)).thenReturn(result(true));
        var response = dashboard(service).getDashboard(MEMBER, SESSION,
                new DashboardQuery(SEOUL.latitude(), SEOUL.longitude(), null));

        assertThat(response.weatherStatus()).isEqualTo(WeatherStatus.STALE);
        assertThat(response.weather().stale()).isTrue();
        assertThat(response.weather().observedAt()).isEqualTo(NOW);
        verify(service).current(MEMBER, SEOUL.latitude(), SEOUL.longitude(), null);
    }

    @Test
    void providerFailureDoesNotDiscardTheDisasterResult() {
        WeatherService service = mock(WeatherService.class);
        when(service.current(any(), any(), any(), isNull())).thenThrow(new ApiProblem(502, "DOWN", "private-provider-detail"));

        var response = dashboard(service).getDashboard(MEMBER, SESSION,
                new DashboardQuery(SEOUL.latitude(), SEOUL.longitude(), null));

        assertThat(response.weatherStatus()).isEqualTo(WeatherStatus.UNAVAILABLE);
        assertThat(response.weather()).isNull();
        assertThat(response.majorDisasters()).containsExactly(TEST_DISASTER);
        assertThat(response.baseLocation()).isEqualTo(SEOUL);
    }

    static Stream<Arguments> authorizationFailures() {
        return Stream.of(
                Arguments.of(new ApiProblem(401, "UNAUTHORIZED", "private-auth-detail"),
                        DashboardException.Reason.UNAUTHENTICATED),
                Arguments.of(new ApiProblem(403, "FORBIDDEN", "private-auth-detail"),
                        DashboardException.Reason.FORBIDDEN),
                Arguments.of(new BadCredentialsException("private-auth-detail"),
                        DashboardException.Reason.UNAUTHENTICATED),
                Arguments.of(new AccessDeniedException("private-auth-detail"),
                        DashboardException.Reason.FORBIDDEN));
    }

    @ParameterizedTest
    @MethodSource("authorizationFailures")
    void downstreamAuthorizationFailureCannotBecomeSuccessfulDashboardData(RuntimeException rejected,
                                                                           DashboardException.Reason reason) {
        WeatherService service = mock(WeatherService.class);
        when(service.current(any(), any(), any(), isNull())).thenThrow(rejected);

        assertThatThrownBy(() -> dashboard(service).getDashboard(MEMBER, SESSION,
                new DashboardQuery(SEOUL.latitude(), SEOUL.longitude(), null)))
                .isInstanceOfSatisfying(DashboardException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure.getMessage()).doesNotContain("private-auth-detail");
                });
    }

    static Stream<Arguments> mismatchedLocations() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(Map.of("latitude", 35.1, "longitude", SEOUL.longitude())),
                Arguments.of(Map.of("latitude", SEOUL.latitude(), "longitude", 129.0)),
                Arguments.of(Map.of("latitude", Double.NaN, "longitude", SEOUL.longitude())),
                Arguments.of(Map.of("latitude", SEOUL.latitude(), "longitude", Double.POSITIVE_INFINITY)),
                Arguments.of(Map.of("latitude", "37.5665", "longitude", SEOUL.longitude())),
                Arguments.of(Map.of("latitude", SEOUL.latitude())));
    }

    @ParameterizedTest
    @MethodSource("mismatchedLocations")
    void observationForAnUnverifiableOrDifferentLocationIsUnavailable(Object suppliedLocation) {
        WeatherService service = mock(WeatherService.class);
        Map<String, Object> result = result(false);
        result.put("location", suppliedLocation);
        when(service.current(any(), any(), any(), isNull())).thenReturn(result);

        var response = dashboard(service).getDashboard(MEMBER, SESSION,
                new DashboardQuery(SEOUL.latitude(), SEOUL.longitude(), null));

        assertThat(response.weather()).isNull();
        assertThat(response.weatherStatus()).isEqualTo(WeatherStatus.UNAVAILABLE);
        assertThat(response.baseLocation()).isEqualTo(SEOUL);
        assertThat(response.majorDisasters()).containsExactly(TEST_DISASTER);
    }

    @Test
    void unexpectedProviderDetailsDoNotEscapeTheAdapter() {
        WeatherService service = mock(WeatherService.class);
        when(service.current(any(), any(), any(), isNull()))
                .thenThrow(new IllegalStateException("private-api-key database-host=private"));

        assertThatThrownBy(() -> new WeatherServiceDashboardReader(service).findWeather(MEMBER, SEOUL))
                .isInstanceOfSatisfying(DashboardException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(DashboardException.Reason.DEPENDENCY_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("private-api-key", "database-host");
                });
    }

    @Test
    void missingOrMalformedFreshnessCannotBecomeAnAvailableObservation() {
        WeatherService service = mock(WeatherService.class);
        Map<String, Object> malformed = result(false);
        malformed.remove("stale");
        when(service.current(any(), any(), any(), isNull())).thenReturn(malformed);

        assertThat(dashboard(service).getDashboard(MEMBER, SESSION,
                new DashboardQuery(SEOUL.latitude(), SEOUL.longitude(), null)).weatherStatus())
                .isEqualTo(WeatherStatus.UNAVAILABLE);

        malformed.put("stale", "false");
        assertThatThrownBy(() -> new WeatherServiceDashboardReader(service).findWeather(MEMBER, SEOUL))
                .isInstanceOfSatisfying(DashboardException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DashboardException.Reason.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void refusesIdentityFreeAndInvalidCoordinateCallsBeforeUsingTheWeatherService() {
        WeatherService service = mock(WeatherService.class);
        var reader = new WeatherServiceDashboardReader(service);
        assertThatThrownBy(() -> reader.findWeather(SEOUL))
                .isInstanceOfSatisfying(DashboardException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DashboardException.Reason.UNAUTHENTICATED));
        assertThatThrownBy(() -> reader.findWeather(null, SEOUL)).isInstanceOf(DashboardException.class);
        assertThatThrownBy(() -> reader.findWeather(MEMBER, new BaseLocation(null, Double.NaN, 127)))
                .isInstanceOf(DashboardException.class);
        verifyNoInteractions(service);
    }

    @Test
    void productionProfileRegistersTheAdapterAgainstTheExistingWeatherService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WeatherService.class, () -> mock(WeatherService.class));
            context.register(WeatherServiceDashboardReader.class);
            context.refresh();
            assertThat(context.getBean(com.disasterinfo.eodiisseo.dashboard.port.DashboardWeatherReader.class))
                    .isInstanceOf(WeatherServiceDashboardReader.class);
        }
    }

    private static DashboardService dashboard(WeatherService service) {
        DashboardLocationReader locations = mock(DashboardLocationReader.class);
        when(locations.resolveCoordinates(SEOUL.latitude(), SEOUL.longitude())).thenReturn(SEOUL);
        return new DashboardService(Optional.of(locations), Optional.of((member, criteria, location) -> List.of(
                new Candidate(TEST_DISASTER, DataOrigin.OFFICIAL, Relevance.RELATED))),
                Optional.of(new WeatherServiceDashboardReader(service)), Optional.of((member, session) -> {
                    assertThat(member).isEqualTo(MEMBER);
                    assertThat(session).isEqualTo(SESSION);
                }));
    }

    private static Map<String, Object> result(boolean stale) {
        Map<String, Object> result = new HashMap<>();
        result.put("location", Map.of("latitude", SEOUL.latitude(), "longitude", SEOUL.longitude()));
        result.put("temperature", new BigDecimal("21.7"));
        result.put("weatherCode", "UNKNOWN");
        result.put("weatherText", "상태 미확인");
        result.put("observedAt", NOW);
        result.put("collectedAt", NOW.plusSeconds(10));
        result.put("conditionBasis", "UNKNOWN");
        result.put("skyValidAt", null);
        result.put("stale", stale);
        result.put("guidance", null);
        return result;
    }
}

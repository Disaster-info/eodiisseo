package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.dashboard.DashboardQuery.Basis;
import com.disasterinfo.eodiisseo.dashboard.DashboardQuery.Criteria;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.DisasterStatus;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.MajorDisaster;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.Weather;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.WeatherStatus;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.Candidate;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.DataOrigin;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.Relevance;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardLocationReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardWeatherReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardServiceTest {

    private static final UUID MEMBER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID INTEREST_AREA_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final DashboardQuery NATIONWIDE = new DashboardQuery(null, null, null);
    private static final DashboardQuery COORDINATES = new DashboardQuery(37.5665, 126.9780, null);
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    void noBasisQueriesNationwideWithoutLocationOrWeatherCalls() {
        FakeReaders fake = new FakeReaders();
        Candidate disaster = candidate("national", DataOrigin.OFFICIAL, DisasterStatus.OCCURRED, Relevance.UNKNOWN);
        fake.candidates = List.of(disaster);

        DashboardResponse response = service(fake).getDashboard(MEMBER_ID, NATIONWIDE);

        assertThat(response.baseLocation()).isNull();
        assertThat(response.weather()).isNull();
        assertThat(response.weatherStatus()).isEqualTo(WeatherStatus.NOT_REQUESTED);
        assertThat(response.majorDisasters()).containsExactly(disaster.disaster());
        assertThat(fake.disasterMemberId).isEqualTo(MEMBER_ID);
        assertThat(fake.disasterCriteria).isEqualTo(new Criteria(Basis.NATIONWIDE, null, null, null));
        assertThat(fake.disasterBaseLocation).isNull();
        assertThat(fake.coordinateCalls).isZero();
        assertThat(fake.interestAreaCalls).isZero();
        assertThat(fake.weatherCalls).isZero();
    }

    @Test
    void noBasisStillWorksWhenLocationAndWeatherAdaptersAreAbsent() {
        FakeReaders fake = new FakeReaders();
        DashboardService service = new DashboardService(Optional.empty(), Optional.of(fake), Optional.empty());

        DashboardResponse response = service.getDashboard(MEMBER_ID, NATIONWIDE);

        assertThat(response.majorDisasters()).isEmpty();
        assertThat(response.weatherStatus()).isEqualTo(WeatherStatus.NOT_REQUESTED);
    }

    @Test
    void filtersMockAndInactiveDisastersAndStablyPrioritizesRelatedCandidates() {
        FakeReaders fake = new FakeReaders();
        Candidate unknownFirst = candidate("unknown-first", DataOrigin.OFFICIAL, DisasterStatus.OCCURRED, Relevance.UNKNOWN);
        Candidate relatedFirst = candidate("related-first", DataOrigin.OFFICIAL, DisasterStatus.ONGOING, Relevance.RELATED);
        Candidate unrelated = candidate("unrelated", DataOrigin.OFFICIAL, DisasterStatus.OCCURRED, Relevance.UNRELATED);
        Candidate relatedSecond = candidate("related-second", DataOrigin.OFFICIAL, DisasterStatus.OCCURRED, Relevance.RELATED);
        Candidate unknownSecond = candidate("unknown-second", DataOrigin.OFFICIAL, DisasterStatus.ONGOING, Relevance.UNKNOWN);
        fake.candidates = List.of(
                unknownFirst,
                candidate("mock-occurred", DataOrigin.MOCK, DisasterStatus.OCCURRED, Relevance.RELATED),
                relatedFirst,
                candidate("ended", DataOrigin.OFFICIAL, DisasterStatus.ENDED, Relevance.RELATED),
                unrelated,
                candidate("lifted", DataOrigin.OFFICIAL, DisasterStatus.LIFTED, Relevance.RELATED),
                relatedSecond,
                candidate("mock-ongoing", DataOrigin.MOCK, DisasterStatus.ONGOING, Relevance.RELATED),
                unknownSecond
        );

        DashboardResponse response = service(fake).getDashboard(MEMBER_ID, NATIONWIDE);

        assertThat(response.majorDisasters()).containsExactly(
                relatedFirst.disaster(), relatedSecond.disaster(),
                unknownFirst.disaster(), unrelated.disaster(), unknownSecond.disaster());
    }

    @Test
    void coordinatesAndAuthenticatedIdentityReachTheCorrectReaders() {
        FakeReaders fake = new FakeReaders();
        fake.weather = weather(false);

        DashboardResponse response = service(fake).getDashboard(MEMBER_ID, COORDINATES);

        assertThat(fake.coordinateCalls).isEqualTo(1);
        assertThat(fake.latitude).isEqualTo(COORDINATES.latitude());
        assertThat(fake.longitude).isEqualTo(COORDINATES.longitude());
        assertThat(fake.interestAreaCalls).isZero();
        assertThat(fake.disasterMemberId).isEqualTo(MEMBER_ID);
        assertThat(fake.disasterCriteria).isEqualTo(new Criteria(
                Basis.CURRENT_LOCATION, COORDINATES.latitude(), COORDINATES.longitude(), null));
        assertThat(fake.disasterBaseLocation).isSameAs(fake.location);
        assertThat(fake.weatherBaseLocation).isSameAs(fake.location);
        assertThat(response.baseLocation()).isSameAs(fake.location);
        assertThat(response.weather()).isSameAs(fake.weather);
    }

    @Test
    void interestAreaLookupReceivesMemberForOwnershipCheckAndUsesResolvedCenter() {
        FakeReaders fake = new FakeReaders();
        fake.location = new BaseLocation("Interest area", 35.1796, 129.0756);

        DashboardResponse response = service(fake).getDashboard(
                MEMBER_ID, new DashboardQuery(null, null, INTEREST_AREA_ID.toString()));

        assertThat(fake.coordinateCalls).isZero();
        assertThat(fake.interestAreaCalls).isEqualTo(1);
        assertThat(fake.locationMemberId).isEqualTo(MEMBER_ID);
        assertThat(fake.interestAreaId).isEqualTo(INTEREST_AREA_ID);
        assertThat(fake.disasterMemberId).isEqualTo(MEMBER_ID);
        assertThat(fake.disasterCriteria).isEqualTo(new Criteria(Basis.INTEREST_AREA, null, null, INTEREST_AREA_ID));
        assertThat(fake.disasterBaseLocation).isSameAs(fake.location);
        assertThat(fake.weatherBaseLocation).isSameAs(fake.location);
        assertThat(response.baseLocation()).isSameAs(fake.location);
    }

    @ParameterizedTest
    @EnumSource(value = DashboardException.Reason.class, names = {"FORBIDDEN", "NOT_FOUND"})
    void interestAreaOwnershipAndMissingAreaFailuresPropagate(DashboardException.Reason reason) {
        FakeReaders fake = new FakeReaders();
        fake.locationFailure = new DashboardException(reason, "Interest area lookup failed.");

        assertThatThrownBy(() -> service(fake).getDashboard(
                MEMBER_ID, new DashboardQuery(null, null, INTEREST_AREA_ID.toString())))
                .isSameAs(fake.locationFailure);

        assertThat(fake.disasterCalls).isZero();
        assertThat(fake.weatherCalls).isZero();
    }

    @ParameterizedTest
    @EnumSource(WeatherCase.class)
    void weatherAvailabilityNeverRemovesSuccessfullyReadDisasters(WeatherCase weatherCase) {
        FakeReaders fake = new FakeReaders();
        Candidate disaster = candidate("retained", DataOrigin.OFFICIAL, DisasterStatus.ONGOING, Relevance.RELATED);
        fake.candidates = List.of(disaster);
        fake.weather = switch (weatherCase) {
            case AVAILABLE -> weather(false);
            case STALE -> weather(true);
            case NULL_RESULT, FAILURE, MISSING_ADAPTER -> null;
        };
        if (weatherCase == WeatherCase.FAILURE) {
            fake.weatherFailure = new IllegalStateException("Weather service unavailable.");
        }
        DashboardService service = new DashboardService(Optional.of(fake), Optional.of(fake),
                weatherCase == WeatherCase.MISSING_ADAPTER ? Optional.empty() : Optional.of(fake));

        DashboardResponse response = service.getDashboard(MEMBER_ID, COORDINATES);

        assertThat(response.majorDisasters()).containsExactly(disaster.disaster());
        assertThat(response.baseLocation()).isSameAs(fake.location);
        assertThat(response.weather()).isSameAs(fake.weather);
        assertThat(response.weatherStatus()).isEqualTo(switch (weatherCase) {
            case AVAILABLE -> WeatherStatus.AVAILABLE;
            case STALE -> WeatherStatus.STALE;
            case NULL_RESULT, FAILURE, MISSING_ADAPTER -> WeatherStatus.UNAVAILABLE;
        });
        assertThat(fake.disasterCalls).isEqualTo(1);
        assertThat(fake.weatherCalls).isEqualTo(weatherCase == WeatherCase.MISSING_ADAPTER ? 0 : 1);
    }

    @Test
    void nullableSeverityAndTemperatureArePassedThroughWithoutInventedDefaults() {
        FakeReaders fake = new FakeReaders();
        Candidate disaster = candidate("nullable", DataOrigin.OFFICIAL, DisasterStatus.OCCURRED, Relevance.RELATED);
        fake.candidates = List.of(disaster);
        fake.weather = weather(false);

        DashboardResponse response = service(fake).getDashboard(MEMBER_ID, COORDINATES);

        assertThat(response.majorDisasters().getFirst()).isSameAs(disaster.disaster());
        assertThat(response.majorDisasters().getFirst().severity()).isNull();
        assertThat(response.weather().temperature()).isNull();
        assertThat(response.weather().observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(response.weatherStatus()).isEqualTo(WeatherStatus.AVAILABLE);
    }

    @Test
    void disasterFailurePropagatesInsteadOfReturningAnEmptyDashboard() {
        FakeReaders fake = new FakeReaders();
        fake.disasterFailure = new IllegalStateException("Disaster query failed.");
        fake.weatherFailure = new IllegalStateException("Weather query also unavailable.");

        assertThatThrownBy(() -> service(fake).getDashboard(MEMBER_ID, COORDINATES))
                .isSameAs(fake.disasterFailure);

        assertThat(fake.disasterCalls).isEqualTo(1);
        assertThat(fake.weatherCalls).isZero();
    }

    @Test
    void missingLocationAdapterFailsExplicitlyWithoutQueryingDisasters() {
        FakeReaders fake = new FakeReaders();
        DashboardService service = new DashboardService(Optional.empty(), Optional.of(fake), Optional.of(fake));

        assertThatThrownBy(() -> service.getDashboard(MEMBER_ID, COORDINATES))
                .isInstanceOfSatisfying(DashboardException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DashboardException.Reason.DEPENDENCY_UNAVAILABLE));

        assertThat(fake.disasterCalls).isZero();
        assertThat(fake.weatherCalls).isZero();
    }

    @Test
    void unresolvedLocationFailsExplicitlyWithoutInventingCoordinates() {
        FakeReaders fake = new FakeReaders();
        fake.location = null;

        assertThatThrownBy(() -> service(fake).getDashboard(MEMBER_ID, COORDINATES))
                .isInstanceOfSatisfying(DashboardException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DashboardException.Reason.DEPENDENCY_UNAVAILABLE));

        assertThat(fake.disasterCalls).isZero();
        assertThat(fake.weatherCalls).isZero();
    }

    @Test
    void coordinateResolutionCannotChangeTheRequestedPoint() {
        FakeReaders fake = new FakeReaders();
        fake.location = new BaseLocation("Different point", 35.1796, 129.0756);

        assertThatThrownBy(() -> service(fake).getDashboard(MEMBER_ID, COORDINATES))
                .isInstanceOfSatisfying(DashboardException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DashboardException.Reason.DEPENDENCY_UNAVAILABLE));
        assertThat(fake.disasterCalls).isZero();
        assertThat(fake.weatherCalls).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidResolvedCoordinates")
    void malformedInterestAreaCentersNeverReachDisasterOrWeatherQueries(double latitude, double longitude) {
        FakeReaders fake = new FakeReaders();
        fake.location = new BaseLocation("Malformed center", latitude, longitude);

        assertThatThrownBy(() -> service(fake).getDashboard(MEMBER_ID,
                new DashboardQuery(null, null, INTEREST_AREA_ID.toString())))
                .isInstanceOfSatisfying(DashboardException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DashboardException.Reason.DEPENDENCY_UNAVAILABLE));
        assertThat(fake.disasterCalls).isZero();
        assertThat(fake.weatherCalls).isZero();
    }

    static Stream<Arguments> invalidResolvedCoordinates() {
        return Stream.of(Arguments.of(Double.NaN, 127.0), Arguments.of(37.0, Double.POSITIVE_INFINITY),
                Arguments.of(90.0001, 127.0), Arguments.of(37.0, -180.0001));
    }

    @Test
    void unauthenticatedRequestsFailBeforeAnyReaderIsCalled() {
        FakeReaders fake = new FakeReaders();

        assertThatThrownBy(() -> service(fake).getDashboard(null, NATIONWIDE))
                .isInstanceOfSatisfying(DashboardException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DashboardException.Reason.UNAUTHENTICATED));

        assertNoReaderCalls(fake);
    }

    @Test
    void nullQueryFailsBeforeAnyReaderIsCalled() {
        FakeReaders fake = new FakeReaders();

        assertThatThrownBy(() -> service(fake).getDashboard(MEMBER_ID, null))
                .isInstanceOfSatisfying(DashboardException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DashboardException.Reason.INVALID_QUERY));

        assertNoReaderCalls(fake);
    }

    @Test
    void invalidQueryFailsBeforeAnyReaderIsCalled() {
        FakeReaders fake = new FakeReaders();

        assertThatThrownBy(() -> service(fake).getDashboard(MEMBER_ID, new DashboardQuery(37.5, null, null)))
                .isInstanceOfSatisfying(DashboardException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(DashboardException.Reason.INVALID_QUERY));

        assertNoReaderCalls(fake);
    }

    private static DashboardService service(FakeReaders fake) {
        return new DashboardService(Optional.of(fake), Optional.of(fake), Optional.of(fake));
    }

    private static void assertNoReaderCalls(FakeReaders fake) {
        assertThat(fake.coordinateCalls).isZero();
        assertThat(fake.interestAreaCalls).isZero();
        assertThat(fake.disasterCalls).isZero();
        assertThat(fake.weatherCalls).isZero();
    }

    private static Candidate candidate(String title, DataOrigin origin, DisasterStatus status, Relevance relevance) {
        MajorDisaster disaster = new MajorDisaster(UUID.nameUUIDFromBytes(title.getBytes(StandardCharsets.UTF_8)),
                "FLOOD", title, "Test region", status, null, OBSERVED_AT, OBSERVED_AT);
        return new Candidate(disaster, origin, relevance);
    }

    private static Weather weather(boolean stale) {
        return new Weather(null, "CLEAR", "Clear", OBSERVED_AT, OBSERVED_AT.plusSeconds(60),
                "OBSERVATION", OBSERVED_AT, stale, "Test guidance");
    }

    private enum WeatherCase {
        AVAILABLE, STALE, NULL_RESULT, FAILURE, MISSING_ADAPTER
    }

    /** Records calls and supplies test fixtures without introducing production adapters. */
    private static final class FakeReaders implements DashboardLocationReader, DashboardDisasterReader, DashboardWeatherReader {
        private BaseLocation location = new BaseLocation("Current area", 37.5665, 126.9780);
        private List<Candidate> candidates = List.of();
        private Weather weather;
        private RuntimeException locationFailure;
        private RuntimeException disasterFailure;
        private RuntimeException weatherFailure;
        private int coordinateCalls;
        private int interestAreaCalls;
        private int disasterCalls;
        private int weatherCalls;
        private double latitude;
        private double longitude;
        private UUID locationMemberId;
        private UUID interestAreaId;
        private UUID disasterMemberId;
        private Criteria disasterCriteria;
        private BaseLocation disasterBaseLocation;
        private BaseLocation weatherBaseLocation;

        @Override
        public BaseLocation resolveCoordinates(double latitude, double longitude) {
            coordinateCalls++;
            this.latitude = latitude;
            this.longitude = longitude;
            if (locationFailure != null) {
                throw locationFailure;
            }
            return location;
        }

        @Override
        public BaseLocation resolveInterestArea(UUID authenticatedMemberId, UUID interestAreaId) {
            interestAreaCalls++;
            locationMemberId = authenticatedMemberId;
            this.interestAreaId = interestAreaId;
            if (locationFailure != null) {
                throw locationFailure;
            }
            return location;
        }

        @Override
        public List<Candidate> findCandidates(UUID authenticatedMemberId, Criteria criteria, BaseLocation baseLocation) {
            disasterCalls++;
            disasterMemberId = authenticatedMemberId;
            disasterCriteria = criteria;
            disasterBaseLocation = baseLocation;
            if (disasterFailure != null) {
                throw disasterFailure;
            }
            return candidates;
        }

        @Override
        public Weather findWeather(BaseLocation baseLocation) {
            weatherCalls++;
            weatherBaseLocation = baseLocation;
            if (weatherFailure != null) {
                throw weatherFailure;
            }
            return weather;
        }
    }
}

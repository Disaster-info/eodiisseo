package com.disasterinfo.eodiisseo.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardQueryTest {

    private static final String INTEREST_AREA_ID = "6a600c58-2412-549c-99ef-062de380a738";

    @Test
    void noParametersSelectNationwideWithoutAnImplicitLocation() {
        DashboardQuery.Criteria criteria = new DashboardQuery(null, null, null).validate();

        assertThat(criteria.basis()).isEqualTo(DashboardQuery.Basis.NATIONWIDE);
        assertThat(criteria.latitude()).isNull();
        assertThat(criteria.longitude()).isNull();
        assertThat(criteria.interestAreaId()).isNull();
    }

    @ParameterizedTest
    @MethodSource("validCoordinates")
    void acceptsCoordinatesIncludingRangeBoundaries(double latitude, double longitude) {
        DashboardQuery.Criteria criteria = new DashboardQuery(latitude, longitude, null).validate();

        assertThat(criteria.basis()).isEqualTo(DashboardQuery.Basis.CURRENT_LOCATION);
        assertThat(criteria.latitude()).isEqualTo(latitude);
        assertThat(criteria.longitude()).isEqualTo(longitude);
        assertThat(criteria.interestAreaId()).isNull();
    }

    static Stream<Arguments> validCoordinates() {
        return Stream.of(
                Arguments.of(37.7381, 127.0337),
                Arguments.of(-90.0, -180.0),
                Arguments.of(-90.0, 180.0),
                Arguments.of(90.0, -180.0),
                Arguments.of(90.0, 180.0),
                Arguments.of(0.0, 0.0)
        );
    }

    @ParameterizedTest
    @MethodSource("validInterestAreaIds")
    void acceptsUuidInterestAreaWithoutCoordinates(String interestAreaId) {
        DashboardQuery.Criteria criteria = new DashboardQuery(null, null, interestAreaId).validate();

        assertThat(criteria.basis()).isEqualTo(DashboardQuery.Basis.INTEREST_AREA);
        assertThat(criteria.interestAreaId()).isEqualTo(UUID.fromString(INTEREST_AREA_ID));
        assertThat(criteria.latitude()).isNull();
        assertThat(criteria.longitude()).isNull();
    }

    static Stream<String> validInterestAreaIds() {
        return Stream.of(INTEREST_AREA_ID, INTEREST_AREA_ID.toUpperCase(Locale.ROOT));
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void rejectsInvalidCriteria(DashboardQuery query) {
        assertThatThrownBy(query::validate)
                .isInstanceOfSatisfying(DashboardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(DashboardException.Reason.INVALID_QUERY));
    }

    static Stream<DashboardQuery> invalidQueries() {
        return Stream.of(
                new DashboardQuery(37.7381, null, null),
                new DashboardQuery(null, 127.0337, null),
                new DashboardQuery(37.7381, 127.0337, INTEREST_AREA_ID),
                new DashboardQuery(37.7381, null, INTEREST_AREA_ID),
                new DashboardQuery(null, 127.0337, INTEREST_AREA_ID),
                new DashboardQuery(90.0001, 127.0337, null),
                new DashboardQuery(-90.0001, 127.0337, null),
                new DashboardQuery(37.7381, 180.0001, null),
                new DashboardQuery(37.7381, -180.0001, null),
                new DashboardQuery(Double.NaN, 127.0337, null),
                new DashboardQuery(37.7381, Double.NaN, null),
                new DashboardQuery(Double.POSITIVE_INFINITY, 127.0337, null),
                new DashboardQuery(Double.NEGATIVE_INFINITY, 127.0337, null),
                new DashboardQuery(37.7381, Double.POSITIVE_INFINITY, null),
                new DashboardQuery(37.7381, Double.NEGATIVE_INFINITY, null),
                new DashboardQuery(null, null, ""),
                new DashboardQuery(null, null, " "),
                new DashboardQuery(null, null, "not-a-uuid"),
                new DashboardQuery(null, null, "1-1-1-1-1"),
                new DashboardQuery(null, null, " " + INTEREST_AREA_ID),
                new DashboardQuery(null, null, INTEREST_AREA_ID + " ")
        );
    }
}

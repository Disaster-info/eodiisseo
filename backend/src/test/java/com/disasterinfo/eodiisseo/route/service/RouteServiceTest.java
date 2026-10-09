package com.disasterinfo.eodiisseo.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import com.disasterinfo.eodiisseo.integration.tmap.TmapResponseDto;
import com.disasterinfo.eodiisseo.integration.tmap.TmapResponseDto.Feature;
import com.disasterinfo.eodiisseo.integration.tmap.TmapResponseDto.Geometry;
import com.disasterinfo.eodiisseo.integration.tmap.TmapResponseDto.Properties;
import com.disasterinfo.eodiisseo.route.dto.ShelterRouteResponse;
import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import com.disasterinfo.eodiisseo.shelter.entity.ShelterType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RouteService의 TMAP 응답 → API-ROUTE-001 응답 변환 단위 테스트.
 * 실제 TMAP을 호출하지 않도록 의존 객체 자리에 null을 넣고 toResponse()만 검증한다 (키·네트워크 없이 CI에서 실행 가능).
 */
class RouteServiceTest {

    private static final GeoPoint ORIGIN = new GeoPoint(37.5567, 126.9236);
    private static final Shelter SHELTER = new Shelter(UUID.fromString("814b9e90-c250-5883-9a0b-e84e620670ba"), "key",
            "테스트 대피소", "주소", 37.5528, 126.9243, ShelterType.EARTHQUAKE_OUTDOOR, 100, null, "행정안전부", null);

    private final RouteService service = new RouteService(null, null);

    @Test
    void TMAP_응답을_명세_형식으로_변환한다() {
        TmapResponseDto tmap = new TmapResponseDto("FeatureCollection", List.of(
                point(126.9236, 37.5567, 632, 513, "SP", 200, "80m 이동"),
                line(List.of(List.of(126.9236, 37.5567), List.of(126.9240, 37.5560))),
                point(126.9240, 37.5560, null, null, "GP", 12, "좌회전"),
                line(List.of(List.of(126.9240, 37.5560), List.of(126.9243, 37.5528))),
                point(126.9243, 37.5528, null, null, "EP", 201, "도착")));

        ShelterRouteResponse res = service.toResponse(ORIGIN, SHELTER, tmap);

        assertThat(res.distanceMeters()).isEqualTo(632);
        assertThat(res.estimatedDurationSeconds()).isEqualTo(513);
        assertThat(res.provider()).isEqualTo("TMAP");
        assertThat(res.coordinateSystem()).isEqualTo("WGS84");
        assertThat(res.origin()).isEqualTo(ORIGIN);
        assertThat(res.destination().shelterId()).isEqualTo(SHELTER.id());
        // [경도, 위도] → (latitude, longitude), 이어지는 구간은 한 구간으로 합치고 겹치는 점은 한 번만
        assertThat(res.routePath()).containsExactly(
                new GeoPoint(37.5567, 126.9236), new GeoPoint(37.5560, 126.9240), new GeoPoint(37.5528, 126.9243));
        assertThat(res.routeSegments()).hasSize(1);
        assertThat(res.guides()).extracting(ShelterRouteResponse.Guide::pointType).containsExactly("SP", "GP", "EP");
    }

    @Test
    void 끊어진_구간은_routeSegments에_따로_담는다() {
        TmapResponseDto tmap = new TmapResponseDto("FeatureCollection", List.of(
                point(126.9236, 37.5567, 632, 513, "SP", 200, "출발"),
                line(List.of(List.of(126.9236, 37.5567), List.of(126.9240, 37.5560))),
                line(List.of(List.of(126.9300, 37.5500), List.of(126.9310, 37.5490)))));

        ShelterRouteResponse res = service.toResponse(ORIGIN, SHELTER, tmap);

        assertThat(res.routeSegments()).hasSize(2);
        assertThat(res.routePath()).hasSize(4);
    }

    @Test
    void 거리_시간을_제공하지_않으면_null로_둔다() {
        TmapResponseDto tmap = new TmapResponseDto("FeatureCollection", List.of(
                point(126.9236, 37.5567, null, null, "SP", 200, "출발"),
                line(List.of(List.of(126.9236, 37.5567), List.of(126.9240, 37.5560)))));

        ShelterRouteResponse res = service.toResponse(ORIGIN, SHELTER, tmap);

        assertThat(res.distanceMeters()).isNull(); // 0 같은 임의 값을 만들지 않는다
        assertThat(res.estimatedDurationSeconds()).isNull();
    }

    @Test
    void 유효한_경로선이_없으면_ROUTE_NOT_FOUND_502() {
        TmapResponseDto onlyPoint = new TmapResponseDto("FeatureCollection", List.of(point(126.9, 37.5, 0, 0, "SP", 200, "")));

        assertThatThrownBy(() -> service.toResponse(ORIGIN, SHELTER, onlyPoint))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ROUTE_NOT_FOUND);
        assertThat(ErrorCode.ROUTE_NOT_FOUND.getStatus().value()).isEqualTo(502);
    }

    // 테스트용 TMAP 응답 조각을 만드는 헬퍼
    private static Feature point(double x, double y, Integer totalDistance, Integer totalTime,
                                 String pointType, Integer turnType, String description) {
        return new Feature(new Geometry("Point", List.of(x, y)),
                new Properties(totalDistance, totalTime, null, null, null, null, description, pointType, turnType, null, null));
    }

    private static Feature line(List<List<Double>> coords) {
        return new Feature(new Geometry("LineString", List.copyOf(coords)),
                new Properties(null, null, null, null, null, null, null, null, null, null, null));
    }
}

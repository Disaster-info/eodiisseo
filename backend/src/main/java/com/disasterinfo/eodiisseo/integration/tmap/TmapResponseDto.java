package com.disasterinfo.eodiisseo.integration.tmap;

import java.util.List;

/**
 * TMAP 보행자 경로안내 응답(GeoJSON FeatureCollection)을 그대로 받는 DTO.
 * 외부 규격 그대로이며 Controller 응답으로 쓰지 않는다 — RouteService가 ShelterRouteResponse(명세 API-ROUTE-001 형식)로 변환한다.
 *
 * 응답 구조 (features 배열에 Point와 LineString이 번갈아 나온다):
 * <pre>
 * features[0] Point      SP(출발)  ← totalDistance·totalTime은 여기에만 있음
 * features[1] LineString 구간 1    ← 경로선 좌표들 + 구간 거리·시간
 * features[2] Point      GP(안내점) ← "좌회전 후 48m 이동" 같은 안내 문구
 * ...
 * features[n] Point      EP(도착)
 * </pre>
 * 응답의 나머지 필드(nearPoiName, facilityType 등)는 쓰지 않아 선언하지 않았다 (모르는 필드는 무시됨).
 */
public record TmapResponseDto(String type, List<Feature> features) {

    /** GeoJSON Feature 하나 = 위치(geometry) + 속성(properties) */
    public record Feature(Geometry geometry, Properties properties) {
    }

    /**
     * Point면 coordinates = [x, y], LineString이면 [[x, y], ...]. 좌표 순서는 [경도, 위도].
     *
     * 타입에 따라 coordinates 모양이 달라서 List&lt;Object&gt;로 받고,
     * point()/line()에서 숫자 배열(double[]{경도, 위도})로 꺼낸다.
     */
    public record Geometry(String type, List<Object> coordinates) {

        public boolean isPoint() {
            return "Point".equals(type);
        }

        public boolean isLineString() {
            return "LineString".equals(type);
        }

        /** Point의 좌표 → {경도, 위도} */
        public double[] point() {
            return toXy(coordinates);
        }

        /** LineString의 좌표 목록 → [{경도, 위도}, ...] */
        public List<double[]> line() {
            return coordinates.stream().map(c -> toXy((List<?>) c)).toList();
        }

        // JSON 숫자는 정수(Integer)나 실수(Double)로 들어올 수 있어 Number로 받아 변환
        private static double[] toXy(List<?> xy) {
            return new double[] {((Number) xy.get(0)).doubleValue(), ((Number) xy.get(1)).doubleValue()};
        }
    }

    /**
     * Feature 속성. 타입마다 들어 있는 필드가 달라 전부 null 가능한 래퍼 타입(Integer)으로 받는다.
     */
    public record Properties(
            Integer totalDistance, // 총 거리(m)       — 첫 Point(SP)에만
            Integer totalTime,     // 총 소요시간(초)   — 첫 Point(SP)에만
            Integer index,         // 전체 Feature 순번
            Integer pointIndex,    // Point 순번
            Integer lineIndex,     // LineString 순번
            String name,           // 도로·지점 이름 (빈 문자열 가능)
            String description,    // 안내 문구 (예: "80m 이동", "좌회전 후 48m 이동")
            String pointType,      // SP 출발 / GP 안내점 / EP 도착 — Point에만
            Integer turnType,      // 회전 코드 (200 직진 등, 코드표 확인 필요) — Point에만
            Integer distance,      // 구간 거리(m)     — LineString에만
            Integer time           // 구간 소요시간(초) — LineString에만
    ) {
    }
}

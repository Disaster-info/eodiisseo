package com.disasterinfo.eodiisseo.route.dto;

import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import java.util.List;
import java.util.UUID;

/**
 * 대피 경로 조회 응답 — API-ROUTE-001 성공 Response.
 *
 * <pre>
 * {"origin": {"latitude": …, "longitude": …},
 *  "destination": {"shelterId": "…", "name": "충현공원", "latitude": …, "longitude": …},
 *  "distanceMeters": 634, "estimatedDurationSeconds": 521,
 *  "routePath": [{"latitude": …, "longitude": …}, …],
 *  "routeSegments": [[{…}, {…}], …],
 *  "provider": "TMAP", "coordinateSystem": "WGS84",
 *  "guides": [{"latitude": …, "longitude": …, "description": "71m 이동", "pointType": "SP", "turnType": 200}, …]}
 * </pre>
 *
 * 경로 API가 준 값만 담고 임의로 보정하지 않는다 (FR-ROUTE-004). 거리·시간을 제공하지 않으면 null.
 *
 * @param distanceMeters           총 이동거리(m). 제공자 미제공 시 null
 * @param estimatedDurationSeconds 총 예상 소요시간(초). 제공자 미제공 시 null
 * @param routePath                전체 경로선 (기존 클라이언트 호환용, 구간을 이어 붙인 것)
 * @param routeSegments            끊어진 LineString별 좌표 배열 — 새 지도 클라이언트는 구간마다 따로 그린다
 * @param guides                   [명세 추가 검토 필요] 처리 내용 ⑨ "추가 이동정보"에 해당하는 TMAP 안내지점(회전·안내 문구).
 *                                 명세 응답 예시에는 없는 필드이므로 API 명세서에 등록 후 확정한다
 */
public record ShelterRouteResponse(
        GeoPoint origin,
        Destination destination,
        Integer distanceMeters,
        Integer estimatedDurationSeconds,
        List<GeoPoint> routePath,
        List<List<GeoPoint>> routeSegments,
        String provider,
        String coordinateSystem,
        List<Guide> guides
) {

    public record Destination(UUID shelterId, String name, double latitude, double longitude) {
    }

    /**
     * @param pointType SP 출발 / GP 안내점 / EP 도착 (TMAP 원문 코드)
     * @param turnType  TMAP 회전 코드. 코드표 확인 전까지 앱에서 문구로 해석하지 않는다
     */
    public record Guide(double latitude, double longitude, String description, String pointType, Integer turnType) {
    }
}

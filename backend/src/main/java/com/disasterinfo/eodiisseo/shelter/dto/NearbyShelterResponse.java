package com.disasterinfo.eodiisseo.shelter.dto;

import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import java.util.List;
import java.util.UUID;

/**
 * 주변 대피소 조회 응답 — API-SHELTER-001 성공 Response.
 *
 * <pre>
 * {"baseLocation": {"latitude": 37.5006, "longitude": 127.0364},
 *  "searchedRadiusKm": 1,
 *  "shelters": [{"shelterId": "…", "name": "충현공원", "address": "…", "latitude": …, "longitude": …, "distanceMeters": 460}]}
 * </pre>
 * 대피소가 없으면 searchedRadiusKm = 최대 반경(5), shelters = [] (404 아님).
 * 상세 정보(유형·수용인원·출처)는 API-SHELTER-002에서 제공한다.
 *
 * @param baseLocation     검색 기준 위치
 * @param searchedRadiusKm 결과를 찾은 반경 (없으면 최대 반경)
 * @param shelters         거리 오름차순, 같은 거리면 shelterId 순
 */
public record NearbyShelterResponse(GeoPoint baseLocation, int searchedRadiusKm, List<Item> shelters) {

    /**
     * @param distanceMeters 기준 위치에서 직선거리(m, 정수). 실제 이동거리는 API-ROUTE-001에서 제공
     */
    public record Item(UUID shelterId, String name, String address, double latitude, double longitude, int distanceMeters) {
    }
}

package com.disasterinfo.eodiisseo.route.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 대피 경로 조회 요청 — API-ROUTE-001 Query Parameter (도착지는 경로 변수 shelterId).
 * GET /api/v1/routes/shelters/{shelterId}?originLatitude=37.5006&originLongitude=127.0364
 *
 * 출발지 위도·경도가 없거나 유효 범위(-90~90, -180~180)를 벗어나면 400 (명세 예외 처리).
 *
 * @param originLatitude  출발 위치 위도 (현재 위치 또는 직접 지정한 출발지)
 * @param originLongitude 출발 위치 경도
 */
public record ShelterRouteRequest(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double originLatitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double originLongitude
) {
}

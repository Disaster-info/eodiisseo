package com.disasterinfo.eodiisseo.shelter.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.util.UUID;

/**
 * 주변 대피소 조회 요청 — API-SHELTER-001 Query Parameter.
 * GET /api/v1/shelters/nearby?latitude=37.5006&longitude=127.0364
 *
 * 명세 규칙: 좌표 또는 관심지역 중 하나는 있어야 하고(없으면 400), 위도·경도는 유효 범위 안이어야 한다(아니면 400).
 * 범위는 명세 공통 기준(위도 -90~90, 경도 -180~180). 국외 좌표는 400이 아니라 빈 결과로 처리된다.
 *
 * @param latitude       기준 위치 위도 (선택, longitude와 함께)
 * @param longitude      기준 위치 경도 (선택, latitude와 함께)
 * @param interestAreaId 기준으로 쓸 관심지역 ID (선택) — 관심지역(AREA) API가 아직 없어 현재는 미지원
 */
public record NearbyShelterRequest(
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        UUID interestAreaId
) {
}

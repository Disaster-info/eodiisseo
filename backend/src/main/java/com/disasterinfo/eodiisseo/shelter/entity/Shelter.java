package com.disasterinfo.eodiisseo.shelter.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * 대피소 한 곳 (서비스 내부 모델). 외부 원본(공공데이터)을 변환해 만들고 ShelterRepository에 보관한다.
 *
 * 아직 JPA Entity가 아니다 — ERD 4.1.0의 shelter 테이블이 생기면 @Entity 매핑으로 바꾼다.
 * 원본에 없는 값은 null로 두고 임의로 채우지 않는다 (FR-SHELTER-013, API-SHELTER-002 예외 처리).
 *
 * @param id           shelterId. 원본 키로 만든 UUID v5 (UuidV5) — 재시작해도 같은 대피소는 같은 ID
 * @param sourceKey    원본 식별 키 (예: "SAFETYDATA:DSSP-IF-10943:1168000000:13")
 * @param capacity     원본 수용가능인원 (비음수) 또는 null. 현재 남은 자리가 아님
 * @param isUnderground 지하 여부. 원본에 없으면 null (SD-001은 제공하지 않음)
 * @param organization 데이터 제공기관 (source.organization)
 * @param updatedAt    원본의 시설별 갱신시각. 확인할 수 없으면 null (임의 생성 금지)
 */
public record Shelter(
        UUID id,
        String sourceKey,
        String name,
        String address,
        double latitude,
        double longitude,
        ShelterType type,
        Integer capacity,
        Boolean isUnderground,
        String organization,
        Instant updatedAt
) {
}

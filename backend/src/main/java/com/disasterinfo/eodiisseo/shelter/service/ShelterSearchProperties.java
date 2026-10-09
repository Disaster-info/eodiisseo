package com.disasterinfo.eodiisseo.shelter.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 주변 대피소 검색 반경 정책값 — API 명세서 "지도 운영 설정과 요청 제한"의 설정 이름을 그대로 쓴다.
 *
 * <pre>
 * SHELTER_INITIAL_RADIUS_KM=1  → initialRadiusKm  처음 검색 반경
 * SHELTER_RADIUS_STEP_KM=1     → radiusStepKm     결과 없을 때 넓히는 크기
 * SHELTER_MAX_RADIUS_KM=5      → maxRadiusKm      최대 반경 (여기까지 없으면 빈 결과)
 * </pre>
 * 명세의 설정 검증(최대 반경 ≥ 초기, 증가값 양수)을 시작 시 수행해 잘못된 값이면 서버가 뜨지 않는다.
 */
@ConfigurationProperties(prefix = "app.shelter")
public record ShelterSearchProperties(int initialRadiusKm, int radiusStepKm, int maxRadiusKm) {

    public ShelterSearchProperties {
        if (initialRadiusKm <= 0 || radiusStepKm <= 0 || maxRadiusKm < initialRadiusKm) {
            throw new IllegalArgumentException("invalid shelter radius settings: initial=" + initialRadiusKm
                    + ", step=" + radiusStepKm + ", max=" + maxRadiusKm);
        }
    }
}

package com.disasterinfo.eodiisseo.shelter.entity;

/**
 * 대피소 유형 — API 명세서 API-SHELTER-002 비고의 additionalInfo.shelterType 값.
 * 응답에는 이 영문 코드 그대로 나가고, 화면 표시용 한글 이름은 앱이 정한다.
 */
public enum ShelterType {
    EARTHQUAKE_OUTDOOR,   // 지진옥외대피장소 (SD-001)
    EARTHQUAKE_TEMPORARY, // 지진겸용 임시주거시설 (SD-002)
    TEMPORARY_HOUSING,    // 이재민 임시주거시설 (SD-003)
    CIVIL_DEFENSE,        // 민방위대피시설 (PD-002)
    UNKNOWN
}

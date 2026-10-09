package com.disasterinfo.eodiisseo.integration.safetydata;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SD-001 지진옥외대피장소(DSSP-IF-10943) 응답의 body 항목 하나. 외부 규격 그대로이며 앱 응답으로 쓰지 않는다.
 *
 * 응답 필드명이 대문자 약어(LO, LA …)라서 @JsonProperty로 자바 이름에 연결한다.
 * 실호출 예 (2026-10-09):
 * {"VT_ACMDFCLTY_NM":"도곡초등학교 운동장","LO":"127.0546…","LA":"37.4990…","VT_ACMD_PSBL_NMPR":6060,
 *  "FCLTY_AR":5000,"USE_SE_CD":"1","ARCD":"1168000000","ACMDFCLTY_SN":13,"TELNO":"02-580-4500", …}
 *
 * @param areaCode      ARCD 지역코드 (시군구 단위, 선행 0 보존을 위해 문자열)
 * @param serialNo      ACMDFCLTY_SN 수용시설 일련번호 — 지역코드 안에서만 고유해 보임 (13, 9, 61 …)
 * @param name          VT_ACMDFCLTY_NM 시설명
 * @param roadAddress   RN_DTL_ADRES 도로명 주소
 * @param address       EQK_ACMDFCLTY_ADRES 대피장소 주소 (지번)
 * @param lng           LO 경도 — 응답에 문자열로 온다
 * @param lat           LA 위도 — 응답에 문자열로 온다
 * @param capacity      VT_ACMD_PSBL_NMPR 수용가능인원 (현재 빈자리가 아님)
 * @param areaM2        FCLTY_AR 시설면적
 * @param useCode       USE_SE_CD 사용구분 (1=사용, 0=미사용)
 * @param tel           TELNO 전화번호
 */
public record EarthquakeShelterItem(
        @JsonProperty("ARCD") String areaCode,
        @JsonProperty("ACMDFCLTY_SN") Long serialNo,
        @JsonProperty("VT_ACMDFCLTY_NM") String name,
        @JsonProperty("RN_DTL_ADRES") String roadAddress,
        @JsonProperty("EQK_ACMDFCLTY_ADRES") String address,
        @JsonProperty("LO") String lng,
        @JsonProperty("LA") String lat,
        @JsonProperty("VT_ACMD_PSBL_NMPR") Integer capacity,
        @JsonProperty("FCLTY_AR") Double areaM2,
        @JsonProperty("USE_SE_CD") String useCode,
        @JsonProperty("TELNO") String tel
) {
}

package com.disasterinfo.eodiisseo.common.response;

/**
 * 응답 공통 좌표 {latitude, longitude} (WGS84). 명세서의 baseLocation·origin·routePath 항목 등에 쓴다.
 */
public record GeoPoint(double latitude, double longitude) {
}

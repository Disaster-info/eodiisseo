package com.disasterinfo.eodiisseo.common.util;

/**
 * 위경도 계산 도구. DB(PostGIS)를 쓰기 전까지 서버에서 직접 거리를 계산할 때 사용한다.
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0088; // 지구 평균 반지름
    private static final double KM_PER_LAT_DEGREE = 111.32;   // 위도 1도 ≈ 111.32km (어디서나 거의 같음)

    private GeoUtils() {
    }

    /**
     * 두 지점의 직선(대원) 거리(km) — 하버사인 공식. 도보 거리가 아니라 "직선거리"다 (FS-SHELTER: 직선거리순).
     */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    /**
     * 국내(제주·독도 포함) 근처 좌표인지 — 국내 공공데이터만 있으므로 밖이면 외부 조회 없이 결과 없음으로 처리할 때 사용.
     * 섬 지역이 잘리지 않도록 넉넉하게 잡은 범위다.
     */
    public static boolean isNearKorea(double lat, double lng) {
        return lat >= 32.5 && lat <= 39.5 && lng >= 123.5 && lng <= 132.5;
    }

    /**
     * 중심에서 반경 radiusKm 원을 감싸는 사각형 범위 {minLat, maxLat, minLng, maxLng}.
     * 외부 API가 사각형 범위 조회만 지원하므로 사각형으로 넉넉히 받고, 원 밖은 distanceKm로 걸러낸다.
     * 경도 1도의 길이는 위도가 높을수록 짧아지므로 cos(위도)로 보정한다.
     */
    public static double[] boundingBox(double lat, double lng, double radiusKm) {
        double dLat = radiusKm / KM_PER_LAT_DEGREE;
        double dLng = radiusKm / (KM_PER_LAT_DEGREE * Math.cos(Math.toRadians(lat)));
        return new double[] {lat - dLat, lat + dLat, lng - dLng, lng + dLng};
    }
}

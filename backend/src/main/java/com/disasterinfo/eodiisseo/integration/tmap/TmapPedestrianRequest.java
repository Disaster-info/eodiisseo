package com.disasterinfo.eodiisseo.integration.tmap;

/**
 * TMAP 보행자 경로안내(POST /tmap/routes/pedestrian) 요청 본문. JSON으로 직렬화되어 그대로 전송된다.
 *
 * 필드명은 TMAP 규격 그대로 둔다 (이름을 바꾸면 TMAP이 인식하지 못함).
 * 앱용 요청(ShelterRouteRequest)과 분리한 이유: 외부 규격이 바뀌어도 앱 API는 영향받지 않게 하려고.
 *
 * 주의: X = 경도(lng), Y = 위도(lat). 앱의 lat/lng 순서와 반대라서 RouteService에서 바꿔 넣는다.
 * startName·endName은 UTF-8 URL 인코딩 값이어야 한다 (TmapClient에서 처리).
 *
 * @param startX       출발 경도
 * @param startY       출발 위도
 * @param endX         도착 경도
 * @param endY         도착 위도
 * @param startName    출발지 이름 (TMAP 필수값)
 * @param endName      도착지 이름 (TMAP 필수값)
 * @param reqCoordType 요청 좌표계 — WGS84GEO(일반 GPS 경위도)
 * @param resCoordType 응답 좌표계 — WGS84GEO로 받아야 지도에 그대로 그릴 수 있다
 * @param searchOption 0 추천 / 4 추천+대로우선 / 10 최단 / 30 최단+계단제외
 */
public record TmapPedestrianRequest(
        double startX,
        double startY,
        double endX,
        double endY,
        String startName,
        String endName,
        String reqCoordType,
        String resCoordType,
        String searchOption
) {

    public static final String WGS84 = "WGS84GEO";
    public static final String SEARCH_RECOMMENDED = "0";

    /** 일반 경위도 좌표 + 추천 경로로 요청을 만드는 편의 메서드 (현재 프로젝트의 기본 사용 방식) */
    public static TmapPedestrianRequest wgs84(double startX, double startY, double endX, double endY,
                                              String startName, String endName) {
        return new TmapPedestrianRequest(startX, startY, endX, endY, startName, endName,
                WGS84, WGS84, SEARCH_RECOMMENDED);
    }
}

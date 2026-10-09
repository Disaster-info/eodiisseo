package com.disasterinfo.eodiisseo.route.service;

import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import com.disasterinfo.eodiisseo.integration.tmap.TmapClient;
import com.disasterinfo.eodiisseo.integration.tmap.TmapPedestrianRequest;
import com.disasterinfo.eodiisseo.integration.tmap.TmapResponseDto;
import com.disasterinfo.eodiisseo.integration.tmap.TmapResponseDto.Feature;
import com.disasterinfo.eodiisseo.route.dto.ShelterRouteRequest;
import com.disasterinfo.eodiisseo.route.dto.ShelterRouteResponse;
import com.disasterinfo.eodiisseo.route.dto.ShelterRouteResponse.Destination;
import com.disasterinfo.eodiisseo.route.dto.ShelterRouteResponse.Guide;
import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import com.disasterinfo.eodiisseo.shelter.service.ShelterService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 대피 경로 비즈니스 로직 (Service 계층) — API-ROUTE-001.
 *
 * 흐름 (명세 처리 내용 ①~⑩):
 *   RouteController → findShelterRoute()
 *     → ShelterService.getShelter(shelterId)  대피소 확인·도착 좌표 (없으면 404)
 *     → TmapClient.pedestrianRoute()          TMAP 보행자 경로 (X=경도, Y=위도)
 *     → toResponse()                          GeoJSON → 명세 응답 형식 변환
 *
 * 다른 기능의 데이터(대피소)는 그 기능의 Service를 통해 가져온다 (Repository 직접 사용 금지).
 * 경로 결과는 요청 시점 계산값이라 저장하지 않으므로 Repository를 두지 않는다.
 */
@Service
public class RouteService {

    static final String PROVIDER = "TMAP";
    static final String COORDINATE_SYSTEM = "WGS84";
    private static final String ORIGIN_NAME = "출발지"; // TMAP은 출발지 이름이 필수

    private final TmapClient tmapClient;
    private final ShelterService shelterService;

    public RouteService(TmapClient tmapClient, ShelterService shelterService) {
        this.tmapClient = tmapClient;
        this.shelterService = shelterService;
    }

    public ShelterRouteResponse findShelterRoute(UUID shelterId, ShelterRouteRequest request) {
        Shelter shelter = shelterService.getShelter(shelterId);
        GeoPoint origin = new GeoPoint(request.originLatitude(), request.originLongitude());
        TmapResponseDto tmap = tmapClient.pedestrianRoute(TmapPedestrianRequest.wgs84(
                origin.longitude(), origin.latitude(), shelter.longitude(), shelter.latitude(),
                ORIGIN_NAME, shelter.name()));
        return toResponse(origin, shelter, tmap);
    }

    /**
     * TMAP GeoJSON → ShelterRouteResponse.
     * features를 순서대로 돌면서 Point는 안내지점(guides)으로, LineString은 경로 구간(routeSegments)으로 모은다.
     * 테스트에서 TMAP 호출 없이 변환만 검증할 수 있도록 package-private으로 분리했다 (RouteServiceTest).
     *
     * @throws BusinessException ROUTE_NOT_FOUND(502) — 유효한 경로선이 없을 때 (명세: 성공 경로를 만들지 않음)
     */
    ShelterRouteResponse toResponse(GeoPoint origin, Shelter shelter, TmapResponseDto tmap) {
        Integer totalDistance = null;
        Integer totalTime = null;
        List<List<GeoPoint>> segments = new ArrayList<>();
        List<Guide> guides = new ArrayList<>();

        List<Feature> features = tmap == null || tmap.features() == null ? List.of() : tmap.features();
        for (Feature f : features) {
            if (f.geometry() == null || f.properties() == null) {
                continue;
            }
            var p = f.properties();
            if (f.geometry().isPoint()) {
                // 총거리·총시간은 첫 Point(SP)에만 있으므로 처음 발견한 값을 사용
                if (totalDistance == null && totalTime == null && (p.totalDistance() != null || p.totalTime() != null)) {
                    totalDistance = p.totalDistance();
                    totalTime = p.totalTime();
                }
                double[] xy = f.geometry().point(); // xy[0]=경도, xy[1]=위도
                guides.add(new Guide(xy[1], xy[0], p.description(), p.pointType(), p.turnType()));
            } else if (f.geometry().isLineString()) {
                appendLine(segments, f.geometry().line().stream().map(xy -> new GeoPoint(xy[1], xy[0])).toList());
            }
        }
        segments.removeIf(s -> s.size() < 2); // 점 하나짜리는 선이 아니다
        if (segments.isEmpty()) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_FOUND);
        }
        List<GeoPoint> routePath = new ArrayList<>();
        for (List<GeoPoint> seg : segments) {
            for (GeoPoint g : seg) {
                if (routePath.isEmpty() || !routePath.getLast().equals(g)) {
                    routePath.add(g);
                }
            }
        }
        return new ShelterRouteResponse(origin,
                new Destination(shelter.id(), shelter.name(), shelter.latitude(), shelter.longitude()),
                totalDistance, totalTime, routePath, segments, PROVIDER, COORDINATE_SYSTEM, guides);
    }

    // 앞 구간의 끝점과 이어지면 같은 구간에 붙이고(겹치는 점은 한 번만), 끊어져 있으면 새 구간을 시작한다
    private static void appendLine(List<List<GeoPoint>> segments, List<GeoPoint> line) {
        if (line.isEmpty()) {
            return;
        }
        List<GeoPoint> last = segments.isEmpty() ? null : segments.getLast();
        if (last != null && last.getLast().equals(line.getFirst())) {
            last.addAll(line.subList(1, line.size()));
        } else {
            segments.add(new ArrayList<>(line));
        }
    }
}

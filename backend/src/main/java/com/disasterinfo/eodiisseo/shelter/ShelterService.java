package com.disasterinfo.eodiisseo.shelter;

import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import com.disasterinfo.eodiisseo.common.util.GeoUtils;
import com.disasterinfo.eodiisseo.common.util.UuidV5;
import com.disasterinfo.eodiisseo.integration.safetydata.EarthquakeShelterItem;
import com.disasterinfo.eodiisseo.integration.safetydata.SafetydataClient;
import com.disasterinfo.eodiisseo.shelter.dto.NearbyShelterRequest;
import com.disasterinfo.eodiisseo.shelter.dto.NearbyShelterResponse;
import com.disasterinfo.eodiisseo.shelter.dto.ShelterDetailResponse;
import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import com.disasterinfo.eodiisseo.shelter.entity.ShelterType;
import com.disasterinfo.eodiisseo.shelter.repository.ShelterRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 대피소 비즈니스 로직 (Service 계층) — API-SHELTER-001 주변 조회, API-SHELTER-002 상세 조회.
 *
 * 주변 조회 흐름 (명세 처리 내용 ①~⑫):
 *   1) 기준 위치 둘레 최대 반경을 감싸는 사각형으로 공공데이터(SD-001)를 조회해 DB(shelter)에 저장
 *      — 미사용(USE_SE_CD=0)으로 바뀐 시설은 조회 대상에서 뺀다
 *   2) DB에서 최대 반경 안의 대피소를 공간 조회 (외부 조회가 실패해도 저장된 대피소로 응답)
 *   3) 초기 반경부터 증가값만큼 넓히며 처음으로 대피소가 있는 반경의 목록을 거리순으로 반환
 *
 * 수집 작업(API-OPS-004 ShelterRefreshGateway)이 연결되면 1)을 빼고 2)·3)만 남긴다.
 * 다른 기능(route)이 대피소가 필요하면 저장소가 아니라 이 Service의 getShelter()를 호출한다.
 */
@Service
public class ShelterService {

    private static final Logger log = LoggerFactory.getLogger(ShelterService.class);

    private static final String SOURCE_KEY_PREFIX = "SAFETYDATA:DSSP-IF-10943:"; // SD-001 원본 키 접두어
    private static final String ORGANIZATION = "행정안전부";
    // DB 반경 조회 여유(1%). 경계에 걸친 대피소가 DB 쪽 계산 오차로 빠지지 않게 넉넉히 받고, 최종 반경 판단은 pickNearest가 한다
    private static final double SEARCH_MARGIN = 1.01;

    private final SafetydataClient safetydataClient;
    private final ShelterRepository shelterRepository;
    private final ShelterSearchProperties radius;
    private final boolean refreshOnQuery;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.disasterinfo.eodiisseo.shelter.repository.InterestAreaLocationRepository interestAreas;

    public ShelterService(SafetydataClient safetydataClient, ShelterRepository shelterRepository, ShelterSearchProperties radius) {
        this(safetydataClient,shelterRepository,radius,false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ShelterService(SafetydataClient safetydataClient,ShelterRepository shelterRepository,ShelterSearchProperties radius,
                          @org.springframework.beans.factory.annotation.Value("${SHELTER_REFRESH_ON_QUERY:false}") boolean refreshOnQuery) {
        this.safetydataClient = safetydataClient;
        this.shelterRepository = shelterRepository;
        this.radius = radius;
        this.refreshOnQuery=refreshOnQuery;
    }

    /** API-SHELTER-001 주변 대피소 조회 */
    public NearbyShelterResponse findNearby(NearbyShelterRequest request) {
        GeoPoint base = baseLocation(request);
        // 국내 데이터만 있으므로 국외 좌표는 외부·DB 조회 없이 빈 결과 (명세: 범위 밖은 결과 없음으로 처리)
        if (!GeoUtils.isNearKorea(base.latitude(), base.longitude())) {
            return pickNearest(base, List.of());
        }
        BusinessException refreshFailure = null;
        try {
            if(refreshOnQuery)refresh(base);
        } catch (BusinessException e) {
            refreshFailure = e;
        }
        List<Shelter> shelters = shelterRepository.findWithin(base.latitude(), base.longitude(),
                radius.maxRadiusKm() * 1000 * SEARCH_MARGIN);
        if (refreshFailure != null) {
            // 저장된 대피소가 없으면 "주변에 대피소 없음"으로 오인하지 않도록 원래 오류(502/504)를 그대로 돌려준다
            if (shelters.isEmpty()) {
                throw refreshFailure;
            }
            log.warn("Shelter refresh failed ({}); answering from stored shelters", refreshFailure.getErrorCode());
        }
        return pickNearest(base, shelters);
    }

    public NearbyShelterResponse findNearby(UUID member,NearbyShelterRequest request) {
        // Check ownership even when the caller also supplied explicit coordinates.
        if(request.interestAreaId()!=null){
            if(interestAreas==null)throw new com.disasterinfo.eodiisseo.auth.exception.ApiProblem(503,"AREA_LOCATION_UNAVAILABLE","관심지역 조회 연결이 필요합니다.");
            if(request.latitude()==null&&request.longitude()==null){
                GeoPoint center=interestAreas.resolve(member,request.interestAreaId());
                return findNearby(new NearbyShelterRequest(center.latitude(),center.longitude(),null));
            }
            interestAreas.verifyOwned(member,request.interestAreaId());
        }
        return findNearby(request);
    }

    /** API-SHELTER-002 대피소 상세 조회 */
    public ShelterDetailResponse getDetail(UUID shelterId) {
        return ShelterDetailResponse.from(getShelter(shelterId));
    }

    /** 다른 기능(대피 경로)용: shelterId로 대피소 조회, 없으면 404 SHELTER_NOT_FOUND */
    public Shelter getShelter(UUID shelterId) {
        return shelterRepository.findById(shelterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHELTER_NOT_FOUND));
    }

    // 기준 위치 둘레를 공공데이터에서 받아 DB에 반영한다. 외부 호출 실패는 BusinessException(502/504)
    private void refresh(GeoPoint base) {
        double[] box = GeoUtils.boundingBox(base.latitude(), base.longitude(), radius.maxRadiusKm());
        List<EarthquakeShelterItem> raw = safetydataClient.earthquakeShelters(box[0], box[1], box[2], box[3]);
        List<Shelter> shelters = toShelters(raw);
        shelterRepository.saveAll(shelters);
        List<UUID> unused = unusedIds(raw, shelters);
        if (!unused.isEmpty()) {
            shelterRepository.deactivateAll(unused);
        }
    }

    // 명세 예외 처리: 좌표와 관심지역이 모두 없으면 거부, 위도·경도는 한 쌍으로만 허용
    private GeoPoint baseLocation(NearbyShelterRequest r) {
        boolean hasLat = r.latitude() != null;
        boolean hasLng = r.longitude() != null;
        if (hasLat != hasLng) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "latitude와 longitude는 함께 보내야 합니다.");
        }
        if (hasLat) {
            return new GeoPoint(r.latitude(), r.longitude());
        }
        if (r.interestAreaId() != null) {
            // 관심지역(AREA) API가 생기면 여기서 회원의 관심지역 좌표를 조회한다
            throw new BusinessException(ErrorCode.INVALID_INPUT, "관심지역 기준 조회는 아직 지원하지 않습니다. 좌표로 조회해 주세요.");
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT, "기준 위치(latitude·longitude 또는 interestAreaId)가 필요합니다.");
    }

    /**
     * 외부 항목 → Shelter. 좌표가 비었거나 국내 범위 밖인 항목, 미사용(USE_SE_CD=0) 시설, 원본 키가 없는 항목은 뺀다.
     * 같은 원본 키가 중복으로 오면 첫 번째만 남긴다.
     */
    List<Shelter> toShelters(List<EarthquakeShelterItem> raw) {
        Map<UUID, Shelter> byId = new LinkedHashMap<>();
        for (EarthquakeShelterItem it : raw) {
            Double lat = parse(it.lat());
            Double lng = parse(it.lng());
            if (lat == null || lng == null || !GeoUtils.isNearKorea(lat, lng) || "0".equals(it.useCode()) || !hasSourceKey(it)) {
                continue;
            }
            String sourceKey = sourceKey(it);
            UUID id = UuidV5.of(sourceKey);
            String address = isBlank(it.roadAddress()) ? it.address() : it.roadAddress();
            Integer capacity = it.capacity() != null && it.capacity() >= 0 ? it.capacity() : null; // 명세: 비음수 정수 또는 null
            byId.putIfAbsent(id, new Shelter(id, sourceKey, it.name(), address, lat, lng,
                    ShelterType.EARTHQUAKE_OUTDOOR, capacity, null, ORGANIZATION, null));
        }
        return List.copyOf(byId.values());
    }

    /** 미사용(USE_SE_CD=0)으로 온 시설의 id. 같은 원본이 사용 중으로도 왔으면 사용 중을 따른다 */
    List<UUID> unusedIds(List<EarthquakeShelterItem> raw, List<Shelter> active) {
        List<UUID> activeIds = active.stream().map(Shelter::id).toList();
        return raw.stream()
                .filter(it -> "0".equals(it.useCode()) && hasSourceKey(it))
                .map(it -> UuidV5.of(sourceKey(it)))
                .filter(id -> !activeIds.contains(id))
                .distinct()
                .toList();
    }

    /**
     * 반경 확대 검색 (명세 ⑤~⑫): 초기 반경 → +증가값 … → 최대 반경 순서로 보며 대피소가 처음 나오는 반경에서 멈춘다.
     * 정렬은 distanceMeters, shelterId 순 (같은 거리일 때도 순서가 고정되도록).
     */
    NearbyShelterResponse pickNearest(GeoPoint base, List<Shelter> shelters) {
        List<NearbyShelterResponse.Item> sorted = shelters.stream()
                .map(s -> new NearbyShelterResponse.Item(s.id(), s.name(), s.address(), s.latitude(), s.longitude(),
                        (int) Math.round(GeoUtils.distanceKm(base.latitude(), base.longitude(), s.latitude(), s.longitude()) * 1000)))
                .sorted(Comparator.comparingInt(NearbyShelterResponse.Item::distanceMeters)
                        .thenComparing(i -> i.shelterId().toString()))
                .toList();
        for (int r : radiusSteps()) {
            int limitMeters = r * 1000;
            List<NearbyShelterResponse.Item> hit = sorted.stream().filter(i -> i.distanceMeters() <= limitMeters).toList();
            if (!hit.isEmpty()) {
                return new NearbyShelterResponse(base, r, hit);
            }
        }
        return new NearbyShelterResponse(base, radius.maxRadiusKm(), List.of());
    }

    // 예: 초기 1, 증가 1, 최대 5 → [1, 2, 3, 4, 5]. 증가값이 최대에 딱 맞지 않아도 마지막은 최대 반경
    private List<Integer> radiusSteps() {
        List<Integer> steps = new ArrayList<>();
        for (int r = radius.initialRadiusKm(); r < radius.maxRadiusKm(); r += radius.radiusStepKm()) {
            steps.add(r);
        }
        steps.add(radius.maxRadiusKm());
        return steps;
    }

    private static boolean hasSourceKey(EarthquakeShelterItem it) {
        return !isBlank(it.areaCode()) && it.serialNo() != null;
    }

    private static String sourceKey(EarthquakeShelterItem it) {
        return SOURCE_KEY_PREFIX + it.areaCode() + ":" + it.serialNo();
    }

    // 좌표가 문자열로 오므로 숫자로 변환. 비었거나 숫자가 아니면 null
    private static Double parse(String s) {
        try {
            return isBlank(s) ? null : Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

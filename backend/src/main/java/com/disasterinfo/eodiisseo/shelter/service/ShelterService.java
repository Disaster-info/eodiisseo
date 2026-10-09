package com.disasterinfo.eodiisseo.shelter.service;

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
import org.springframework.stereotype.Service;

/**
 * 대피소 비즈니스 로직 (Service 계층) — API-SHELTER-001 주변 조회, API-SHELTER-002 상세 조회.
 *
 * 주변 조회 흐름 (명세 처리 내용 ①~⑫):
 *   1) 기준 위치 둘레 최대 반경을 감싸는 사각형으로 공공데이터(SD-001)를 한 번 조회
 *   2) 원본 → Shelter 변환(좌표 이상·미사용 시설 제외, UUID v5 발급) 후 저장소에 보관 (상세·경로 조회용)
 *   3) 초기 반경부터 증가값만큼 넓히며 처음으로 대피소가 있는 반경의 목록을 거리순으로 반환
 *
 * DB가 생기면 1)을 수집(collection) 결과 조회로 바꾸면 되고, 2)의 저장소는 JPA 구현으로 교체한다.
 * 다른 기능(route)이 대피소가 필요하면 저장소가 아니라 이 Service의 getShelter()를 호출한다.
 */
@Service
public class ShelterService {

    private static final String SOURCE_KEY_PREFIX = "SAFETYDATA:DSSP-IF-10943:"; // SD-001 원본 키 접두어
    private static final String ORGANIZATION = "행정안전부";

    private final SafetydataClient safetydataClient;
    private final ShelterRepository shelterRepository;
    private final ShelterSearchProperties radius;

    public ShelterService(SafetydataClient safetydataClient, ShelterRepository shelterRepository, ShelterSearchProperties radius) {
        this.safetydataClient = safetydataClient;
        this.shelterRepository = shelterRepository;
        this.radius = radius;
    }

    /** API-SHELTER-001 주변 대피소 조회 */
    public NearbyShelterResponse findNearby(NearbyShelterRequest request) {
        GeoPoint base = baseLocation(request);
        List<Shelter> shelters = List.of();
        // 국내 데이터만 있으므로 국외 좌표는 외부 호출 없이 빈 결과 (명세: 범위 밖은 결과 없음으로 처리)
        if (GeoUtils.isNearKorea(base.latitude(), base.longitude())) {
            double[] box = GeoUtils.boundingBox(base.latitude(), base.longitude(), radius.maxRadiusKm());
            shelters = toShelters(safetydataClient.earthquakeShelters(box[0], box[1], box[2], box[3]));
            shelterRepository.saveAll(shelters);
        }
        return pickNearest(base, shelters);
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
            // 관심지역(AREA) API·DB가 생기면 여기서 회원의 관심지역 좌표를 조회한다
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
            if (lat == null || lng == null || !GeoUtils.isNearKorea(lat, lng) || "0".equals(it.useCode())
                    || isBlank(it.areaCode()) || it.serialNo() == null) {
                continue;
            }
            String sourceKey = SOURCE_KEY_PREFIX + it.areaCode() + ":" + it.serialNo();
            UUID id = UuidV5.of(sourceKey);
            String address = isBlank(it.roadAddress()) ? it.address() : it.roadAddress();
            Integer capacity = it.capacity() != null && it.capacity() >= 0 ? it.capacity() : null; // 명세: 비음수 정수 또는 null
            byId.putIfAbsent(id, new Shelter(id, sourceKey, it.name(), address, lat, lng,
                    ShelterType.EARTHQUAKE_OUTDOOR, capacity, null, ORGANIZATION, null));
        }
        return List.copyOf(byId.values());
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

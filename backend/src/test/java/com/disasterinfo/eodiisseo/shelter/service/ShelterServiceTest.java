package com.disasterinfo.eodiisseo.shelter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import com.disasterinfo.eodiisseo.integration.safetydata.EarthquakeShelterItem;
import com.disasterinfo.eodiisseo.shelter.dto.NearbyShelterRequest;
import com.disasterinfo.eodiisseo.shelter.dto.NearbyShelterResponse;
import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import com.disasterinfo.eodiisseo.shelter.entity.ShelterType;
import com.disasterinfo.eodiisseo.shelter.repository.InMemoryShelterRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ShelterService 단위 테스트 — 원본 변환, 반경 확대·정렬, 입력 검증, 상세 조회. 외부 API 없이 실행된다.
 */
class ShelterServiceTest {

    private static final GeoPoint BASE = new GeoPoint(37.5006, 127.0364); // 역삼역

    private final InMemoryShelterRepository repository = new InMemoryShelterRepository();
    private final ShelterService service = new ShelterService(null, repository, new ShelterSearchProperties(1, 1, 5));

    @Test
    void 좌표_이상_미사용_원본키_없는_시설은_제외하고_같은_원본은_같은_UUID() {
        List<Shelter> shelters = service.toShelters(List.of(
                item("1168000000", 13L, "도곡초등학교 운동장", "37.49905524723655", "127.05466431467862", "1"),
                item("1168000000", 14L, "좌표 없음", null, "127.05", "1"),
                item("1168000000", 15L, "미사용 시설", "37.50", "127.04", "0"),
                item("1168000000", 16L, "위경도 뒤바뀜", "127.04", "37.50", "1"),
                item(null, 17L, "원본 키 없음", "37.50", "127.04", "1")));

        assertThat(shelters).extracting(Shelter::name).containsExactly("도곡초등학교 운동장");
        Shelter s = shelters.get(0);
        assertThat(s.id().version()).isEqualTo(5);
        assertThat(s.id()).isEqualTo(service.toShelters(List.of(item("1168000000", 13L, "x", "37.5", "127.0", "1"))).get(0).id());
        assertThat(s.type()).isEqualTo(ShelterType.EARTHQUAKE_OUTDOOR);
        assertThat(s.address()).isEqualTo("서울특별시 강남구 선릉로64길 33(대치동)"); // 도로명 우선
        assertThat(s.updatedAt()).isNull(); // 원본에 시설별 갱신시각 없음 → 만들지 않음
    }

    @Test
    void 처음으로_대피소가_있는_반경에서_멈추고_거리순_정렬() {
        // 역삼역 기준 약 1.6km(도곡초) / 약 0.46km(충현공원 근처) / 약 4km
        List<Shelter> shelters = List.of(
                shelter("far", 37.4650, 127.0364), shelter("mid", 37.4990, 127.0547), shelter("near", 37.5047, 127.0364));

        NearbyShelterResponse res = service.pickNearest(BASE, shelters);

        assertThat(res.searchedRadiusKm()).isEqualTo(1);
        assertThat(res.baseLocation()).isEqualTo(BASE);
        assertThat(res.shelters()).extracting(NearbyShelterResponse.Item::name).containsExactly("near");
        assertThat(res.shelters().get(0).distanceMeters()).isBetween(440, 470);
    }

    @Test
    void 최대_반경까지_없으면_searchedRadiusKm_5와_빈_목록() {
        NearbyShelterResponse res = service.pickNearest(BASE, List.of(shelter("far", 37.40, 127.0364))); // 약 11km

        assertThat(res.searchedRadiusKm()).isEqualTo(5);
        assertThat(res.shelters()).isEmpty();
    }

    @Test
    void 위도_경도는_함께_보내야_하고_기준_위치가_없으면_400() {
        assertThatThrownBy(() -> service.findNearby(new NearbyShelterRequest(37.5, null, null)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.findNearby(new NearbyShelterRequest(null, null, null)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void 국외_좌표는_외부_호출_없이_빈_결과() {
        // safetydataClient가 null이라 호출되면 NPE — 빈 결과가 나오면 호출하지 않은 것
        NearbyShelterResponse res = service.findNearby(new NearbyShelterRequest(40.7128, -74.0060, null));

        assertThat(res.shelters()).isEmpty();
        assertThat(res.searchedRadiusKm()).isEqualTo(5);
    }

    @Test
    void 상세는_저장된_대피소만_조회되고_없으면_SHELTER_NOT_FOUND() {
        Shelter s = shelter("saved", 37.5, 127.0);
        repository.saveAll(List.of(s));

        assertThat(service.getDetail(s.id()).additionalInfo().shelterType()).isEqualTo(ShelterType.EARTHQUAKE_OUTDOOR);
        assertThatThrownBy(() -> service.getDetail(UUID.randomUUID()))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SHELTER_NOT_FOUND);
    }

    private static EarthquakeShelterItem item(String arcd, Long sn, String name, String lat, String lng, String use) {
        return new EarthquakeShelterItem(arcd, sn, name, "서울특별시 강남구 선릉로64길 33(대치동)",
                "서울특별시 강남구 대치동 924-10", lng, lat, 6060, 5000.0, use, "02-580-4500");
    }

    private static Shelter shelter(String name, double lat, double lng) {
        return new Shelter(UUID.nameUUIDFromBytes(name.getBytes()), name, name, "주소", lat, lng,
                ShelterType.EARTHQUAKE_OUTDOOR, null, null, "행정안전부", null);
    }
}

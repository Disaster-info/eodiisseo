package com.disasterinfo.eodiisseo.shelter.dto;

import com.disasterinfo.eodiisseo.shelter.entity.Shelter;
import com.disasterinfo.eodiisseo.shelter.entity.ShelterType;
import java.time.Instant;
import java.util.UUID;

/**
 * 대피소 상세 응답 — API-SHELTER-002 성공 Response.
 *
 * <pre>
 * {"shelterId": "…", "name": "도곡초등학교 운동장", "address": "…", "latitude": …, "longitude": …,
 *  "additionalInfo": {"shelterType": "EARTHQUAKE_OUTDOOR", "capacity": 6060, "isUnderground": null},
 *  "source": {"organization": "행정안전부"},
 *  "updatedAt": null}
 * </pre>
 * 원본에 없는 값은 null (임의 생성 금지). SD-001은 시설별 갱신시각·지하 여부를 제공하지 않아 null이다.
 */
public record ShelterDetailResponse(
        UUID shelterId,
        String name,
        String address,
        double latitude,
        double longitude,
        AdditionalInfo additionalInfo,
        Source source,
        Instant updatedAt
) {

    /** 명세 비고의 additionalInfo 항목만 담는다 (출처 없는 속성은 만들지 않음) */
    public record AdditionalInfo(ShelterType shelterType, Integer capacity, Boolean isUnderground) {
    }

    public record Source(String organization) {
    }

    public static ShelterDetailResponse from(Shelter s) {
        return new ShelterDetailResponse(s.id(), s.name(), s.address(), s.latitude(), s.longitude(),
                new AdditionalInfo(s.type(), s.capacity(), s.isUnderground()),
                new Source(s.organization()),
                s.updatedAt());
    }
}

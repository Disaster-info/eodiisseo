package com.disasterinfo.eodiisseo.shelter.controller;

import com.disasterinfo.eodiisseo.shelter.dto.NearbyShelterRequest;
import com.disasterinfo.eodiisseo.shelter.dto.NearbyShelterResponse;
import com.disasterinfo.eodiisseo.shelter.dto.ShelterDetailResponse;
import com.disasterinfo.eodiisseo.shelter.service.ShelterService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대피소 API (Controller 계층) — API 명세서 SHELTER-대피소.
 * 검증 → Service 호출 → 응답 DTO를 그대로 반환한다 (성공 응답은 감싸지 않음, 오류는 GlobalExceptionHandler).
 *
 * TODO 인증: 명세상 "인증 필요"(Bearer). 로그인(AUTH) 구현 후 적용
 */
@RestController
@RequestMapping("/api/v1/shelters")
public class ShelterController {

    private final ShelterService shelterService;

    public ShelterController(ShelterService shelterService) {
        this.shelterService = shelterService;
    }

    /**
     * API-SHELTER-001 주변 대피소 조회.
     * GET /api/v1/shelters/nearby?latitude=37.5006&longitude=127.0364
     */
    @GetMapping("/nearby")
    public NearbyShelterResponse nearby(@Valid @ModelAttribute NearbyShelterRequest request) {
        return shelterService.findNearby(request);
    }

    /**
     * API-SHELTER-002 대피소 상세정보 조회.
     * GET /api/v1/shelters/{shelterId} — UUID 형식이 아니면 400, 없으면 404
     */
    @GetMapping("/{shelterId}")
    public ShelterDetailResponse detail(@PathVariable UUID shelterId) {
        return shelterService.getDetail(shelterId);
    }
}

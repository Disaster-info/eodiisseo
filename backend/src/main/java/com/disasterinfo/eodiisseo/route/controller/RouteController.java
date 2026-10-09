package com.disasterinfo.eodiisseo.route.controller;

import com.disasterinfo.eodiisseo.route.dto.ShelterRouteRequest;
import com.disasterinfo.eodiisseo.route.dto.ShelterRouteResponse;
import com.disasterinfo.eodiisseo.route.service.RouteService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대피 경로 API (Controller 계층) — API 명세서 ROUTE-대피경로.
 *
 * Controller는 HTTP 입출력만 담당한다:
 * 1. 경로 변수(shelterId)와 쿼리 파라미터(출발 좌표)를 받아 @Valid로 검증 (실패 시 GlobalExceptionHandler가 400)
 * 2. RouteService 호출
 * 3. 응답 DTO를 그대로 반환 (성공 응답은 감싸지 않음)
 * 비즈니스 로직·외부 호출·try-catch는 여기 두지 않는다.
 *
 * TODO 인증·호출 제한: 명세상 "인증 필요"(Bearer), 회원당 분당 10회 초과 시 429 RATE_LIMITED. 로그인(AUTH) 구현 후 적용
 */
@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    /**
     * API-ROUTE-001 대피 경로 조회 (FR-ROUTE-001~003).
     * GET /api/v1/routes/shelters/{shelterId}?originLatitude=37.5006&originLongitude=127.0364
     *
     * 조회만 하고 서버 상태를 바꾸지 않으므로 GET. @ModelAttribute는 쿼리 파라미터를 record 필드에 이름으로 매핑한다.
     */
    @GetMapping("/shelters/{shelterId}")
    public ShelterRouteResponse shelterRoute(@PathVariable UUID shelterId, @Valid @ModelAttribute ShelterRouteRequest request) {
        return routeService.findShelterRoute(shelterId, request);
    }
}

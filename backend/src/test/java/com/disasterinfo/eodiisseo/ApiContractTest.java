package com.disasterinfo.eodiisseo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.exception.GlobalExceptionHandler;
import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import com.disasterinfo.eodiisseo.route.controller.RouteController;
import com.disasterinfo.eodiisseo.route.dto.ShelterRouteResponse;
import com.disasterinfo.eodiisseo.route.service.RouteService;
import com.disasterinfo.eodiisseo.shelter.controller.ShelterController;
import com.disasterinfo.eodiisseo.shelter.dto.NearbyShelterResponse;
import com.disasterinfo.eodiisseo.shelter.service.ShelterService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * API 명세서 5.2.1과의 계약(URL·필드 이름·오류 형식) 검증.
 * Service는 가짜(mock)로 두고 Controller·예외 처리·JSON 직렬화만 확인한다 — 팀원이 명세서를 보고 만든 클라이언트와 맞는지 보장.
 */
class ApiContractTest {

    private static final UUID SHELTER_ID = UUID.fromString("814b9e90-c250-5883-9a0b-e84e620670ba");

    private final ShelterService shelterService = mock(ShelterService.class);
    private final RouteService routeService = mock(RouteService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders
                .standaloneSetup(new ShelterController(shelterService), new RouteController(routeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void SHELTER_001_성공_응답은_감싸지_않은_명세_필드() throws Exception {
        when(shelterService.findNearby(any())).thenReturn(new NearbyShelterResponse(new GeoPoint(37.5006, 127.0364), 1,
                List.of(new NearbyShelterResponse.Item(SHELTER_ID, "충현공원", "서울특별시 강남구 역삼동 666-2", 37.5047, 127.0364, 460))));

        mvc.perform(get("/api/v1/shelters/nearby").param("latitude", "37.5006").param("longitude", "127.0364"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceIdFilter.HEADER))
                .andExpect(jsonPath("$.baseLocation.latitude").value(37.5006))
                .andExpect(jsonPath("$.searchedRadiusKm").value(1))
                .andExpect(jsonPath("$.shelters[0].shelterId").value(SHELTER_ID.toString()))
                .andExpect(jsonPath("$.shelters[0].distanceMeters").value(460))
                .andExpect(jsonPath("$.success").doesNotExist()); // 예전 {success, data} 감싸기가 없어야 함
    }

    @Test
    void 검증_실패는_400_code_message_traceId() throws Exception {
        mvc.perform(get("/api/v1/shelters/nearby").param("latitude", "91").param("longitude", "127"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void SHELTER_002_UUID_아니면_400_없으면_404() throws Exception {
        mvc.perform(get("/api/v1/shelters/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        when(shelterService.getDetail(SHELTER_ID)).thenThrow(new BusinessException(ErrorCode.SHELTER_NOT_FOUND));
        mvc.perform(get("/api/v1/shelters/" + SHELTER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHELTER_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void ROUTE_001_URL과_응답_필드() throws Exception {
        when(routeService.findShelterRoute(eq(SHELTER_ID), any())).thenReturn(new ShelterRouteResponse(
                new GeoPoint(37.5006, 127.0364),
                new ShelterRouteResponse.Destination(SHELTER_ID, "충현공원", 37.5047, 127.0364),
                634, null,
                List.of(new GeoPoint(37.5006, 127.0364), new GeoPoint(37.5047, 127.0364)),
                List.of(List.of(new GeoPoint(37.5006, 127.0364), new GeoPoint(37.5047, 127.0364))),
                "TMAP", "WGS84", List.of()));

        mvc.perform(get("/api/v1/routes/shelters/" + SHELTER_ID)
                        .param("originLatitude", "37.5006").param("originLongitude", "127.0364"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin.latitude").value(37.5006))
                .andExpect(jsonPath("$.destination.shelterId").value(SHELTER_ID.toString()))
                .andExpect(jsonPath("$.distanceMeters").value(634))
                .andExpect(jsonPath("$.estimatedDurationSeconds").isEmpty()) // 미제공은 null
                .andExpect(jsonPath("$.routePath[1].longitude").value(127.0364))
                .andExpect(jsonPath("$.routeSegments[0][0].latitude").value(37.5006))
                .andExpect(jsonPath("$.provider").value("TMAP"))
                .andExpect(jsonPath("$.coordinateSystem").value("WGS84"));
    }

    @Test
    void ROUTE_001_출발_좌표_없으면_400_경로선_없으면_502() throws Exception {
        mvc.perform(get("/api/v1/routes/shelters/" + SHELTER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        when(routeService.findShelterRoute(eq(SHELTER_ID), any())).thenThrow(new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
        mvc.perform(get("/api/v1/routes/shelters/" + SHELTER_ID)
                        .param("originLatitude", "37.5").param("originLongitude", "127.0"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }
}

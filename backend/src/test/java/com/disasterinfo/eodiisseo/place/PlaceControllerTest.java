package com.disasterinfo.eodiisseo.place;

import com.disasterinfo.eodiisseo.common.exception.*;
import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlaceControllerTest {
    private final PlaceService service = mock(PlaceService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlaceController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void invalidInputNeverCallsProvider() throws Exception {
        for (String keyword : new String[]{"", " ", "a".repeat(101)})
            mvc.perform(get("/api/v1/places/search").param("keyword",keyword)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places/search")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places/geocode").param("address"," ")).andExpect(status().isBadRequest());
        for (String latitude : new String[]{"91", "-91", "NaN", "Infinity", "oops"})
            mvc.perform(get("/api/v1/places/reverse-geocode").param("latitude",latitude).param("longitude","127"))
                    .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places/reverse-geocode").param("latitude","37").param("longitude","181"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/places/reverse-geocode")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void responseIsNotWrapped() throws Exception {
        when(service.geocode("서울")).thenReturn(new GeoPoint(37.5,127));
        mvc.perform(get("/api/v1/places/geocode").param("address","서울")).andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(37.5)).andExpect(jsonPath("$.longitude").value(127));
        mvc.perform(get("/api/v1/places/search").param("keyword","역")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void unavailableKeyAndRateLimitKeepTheirStatus() throws Exception {
        when(service.search("역")).thenThrow(new BusinessException(ErrorCode.PLACE_API_NOT_CONFIGURED));
        mvc.perform(get("/api/v1/places/search").param("keyword","역")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PLACE_API_NOT_CONFIGURED"));
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(service).search("역");
        mvc.perform(get("/api/v1/places/search").param("keyword","역")).andExpect(status().isTooManyRequests());
    }
}

package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.exception.GlobalExceptionHandler;
import com.disasterinfo.eodiisseo.shelter.ShelterService;
import com.disasterinfo.eodiisseo.shelter.controller.ShelterController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class DashboardHttpErrorsTest {
    private static final String PRIVATE_DETAIL = "private-member coordinate=secret database-host=private";
    private final ShelterService shelters = mock(ShelterService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ErrorFixture(), new ShelterController(shelters))
                .setControllerAdvice(new GlobalExceptionHandler(), new DashboardHttpErrors())
                .addFilters(new TraceIdFilter()).build();
    }

    @ParameterizedTest
    @EnumSource(DashboardException.Reason.class)
    void domainErrorsUseTheirHttpMeaningAndTheSharedBodyWithoutDetails(DashboardException.Reason reason) throws Exception {
        int status = switch (reason) {
            case INVALID_QUERY -> 400;
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case DEPENDENCY_UNAVAILABLE, POLICY_UNRESOLVED -> 500;
        };
        String code = switch (reason) {
            case INVALID_QUERY -> "INVALID_INPUT";
            case UNAUTHENTICATED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case DEPENDENCY_UNAVAILABLE, POLICY_UNRESOLVED -> "INTERNAL_ERROR";
        };
        assertError(mvc.perform(get("/__test/dashboard-errors/" + reason)).andReturn(), status, code);
    }

    @Test
    void unexpectedFailuresContinueThroughTheExistingGlobalHandler() throws Exception {
        assertError(mvc.perform(get("/__test/dashboard-errors/unexpected")).andReturn(), 500, "INTERNAL_ERROR");
    }

    @Test
    void scopedAdviceDoesNotChangeErrorsFromOtherDomainsControllers() throws Exception {
        UUID id = UUID.randomUUID();
        when(shelters.getDetail(id)).thenThrow(new DashboardException(DashboardException.Reason.FORBIDDEN, PRIVATE_DETAIL));
        assertError(mvc.perform(get("/api/v1/shelters/" + id)).andReturn(), 500, "INTERNAL_ERROR");
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        var body = json.readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "traceId");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("message").asString()).isNotBlank();
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).isNotBlank();
        assertThat(body.get("traceId").asString()).isEqualTo(traceId);
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_DETAIL);
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    /** Explicit standalone MockMvc fixture, excluded from the full application's component scan. */
    @TestComponent
    @RestController
    static class ErrorFixture {
        @GetMapping("/__test/dashboard-errors/{reason}")
        void dashboard(@PathVariable("reason") DashboardException.Reason reason) {
            throw new DashboardException(reason, PRIVATE_DETAIL);
        }

        @GetMapping("/__test/dashboard-errors/unexpected")
        void unexpected() {
            throw new IllegalStateException(PRIVATE_DETAIL);
        }
    }
}

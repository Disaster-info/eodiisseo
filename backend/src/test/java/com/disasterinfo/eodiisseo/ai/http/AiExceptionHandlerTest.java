package com.disasterinfo.eodiisseo.ai.http;

import com.disasterinfo.eodiisseo.ai.chat.ChatException;
import com.disasterinfo.eodiisseo.ai.summary.SummaryException;
import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.Profile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Test-only HTTP handlers exercise advice precedence without enabling the protected AI APIs. */
class AiExceptionHandlerTest {
    private static final String PRIVATE_DETAIL = "private-question token=secret database-host=private";
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ErrorFixture())
                .setControllerAdvice(new GlobalExceptionHandler(), new AiExceptionHandler())
                .addFilters(new TraceIdFilter()).build();
    }

    @ParameterizedTest
    @EnumSource(ChatException.Reason.class)
    void chatReasonsRetainTheirStatusAndUseTheSharedBodyWithoutFailureDetails(ChatException.Reason reason) throws Exception {
        String code = switch (reason) {
            case INVALID_QUESTION -> "INVALID_INPUT";
            case UNAUTHENTICATED -> "UNAUTHORIZED";
            default -> "INTERNAL_ERROR";
        };
        assertError(mvc.perform(get("/__test/ai-errors/chat/" + reason)).andReturn(), reason.httpStatus(), code);
    }

    @ParameterizedTest
    @EnumSource(SummaryException.Reason.class)
    void summaryReasonsRetainClientErrorStatusesAlongsideTheGlobalAdvice(SummaryException.Reason reason) throws Exception {
        int status = switch (reason) {
            case INVALID_DISASTER_ID -> 400;
            case UNAUTHENTICATED -> 401;
            case NOT_FOUND -> 404;
            case DEPENDENCY_UNAVAILABLE -> 500;
        };
        String code = switch (reason) {
            case INVALID_DISASTER_ID -> "INVALID_INPUT";
            case UNAUTHENTICATED -> "UNAUTHORIZED";
            case NOT_FOUND -> "NOT_FOUND";
            case DEPENDENCY_UNAVAILABLE -> "INTERNAL_ERROR";
        };
        assertError(mvc.perform(get("/__test/ai-errors/summary/" + reason)).andReturn(), status, code);
    }

    @Test
    void unexpectedFailuresStillUseTheExistingGlobalHandler() throws Exception {
        assertError(mvc.perform(get("/__test/ai-errors/unexpected")).andReturn(), 500, "INTERNAL_ERROR");
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        var response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        var body = json.readTree(response.getContentAsString());
        assertThat(body.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("code", "message", "traceId");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("message").asString()).isNotBlank();
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).isNotBlank();
        assertThat(body.get("traceId").asString()).isEqualTo(traceId);
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_DETAIL);
    }

    @RestController
    @RequestMapping("/__test/ai-errors")
    @Profile("ai-http-error-test")
    static class ErrorFixture {
        @GetMapping("/chat/{reason}")
        void chat(@PathVariable("reason") ChatException.Reason reason) {
            throw new ChatException(reason, PRIVATE_DETAIL);
        }

        @GetMapping("/summary/{reason}")
        void summary(@PathVariable("reason") SummaryException.Reason reason) {
            throw new SummaryException(reason);
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException(PRIVATE_DETAIL);
        }
    }
}

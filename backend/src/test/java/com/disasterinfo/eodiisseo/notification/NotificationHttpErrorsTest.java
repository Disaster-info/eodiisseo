package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.exception.GlobalExceptionHandler;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.Authentication;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.MemberSession;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.Store;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.SettingsResponse;
import com.disasterinfo.eodiisseo.shelter.ShelterService;
import com.disasterinfo.eodiisseo.shelter.controller.ShelterController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationHttpErrorsTest {
    private MockMvc mvc;
    private final ShelterService shelters = mock(ShelterService.class);
    private final Authentication authentication = mock(Authentication.class);
    private final Store store = mock(Store.class);
    private final MemberSession session = new MemberSession(UUID.randomUUID(), UUID.randomUUID());

    @BeforeEach
    void setUp() {
        var notifications = new NotificationService(Optional.of(authentication), Optional.of(store),
                Optional.empty(), Optional.empty(), Optional.empty());
        mvc = MockMvcBuilders.standaloneSetup(new ErrorsProbe(), new ServiceProbe(notifications, session),
                        new ShelterController(shelters))
                .setControllerAdvice(new GlobalExceptionHandler(), new NotificationHttpErrors())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @ParameterizedTest
    @EnumSource(NotificationException.Reason.class)
    void domainStatusAndCodeSurviveTheSharedGenericHandler(NotificationException.Reason reason) throws Exception {
        var response = mvc.perform(get("/notification-errors/" + reason))
                .andExpect(status().is(reason.status()))
                .andExpect(jsonPath("$.code").value(reason.name()))
                .andExpect(jsonPath("$.message").value(new NotificationException(reason).getMessage()))
                .andReturn().getResponse();

        var body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "traceId");
        assertThat(body.get("traceId").asString()).isNotBlank().isEqualTo(response.getHeader(TraceIdFilter.HEADER));
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void adviceDoesNotChangeAnUnownedControllersErrorContract() throws Exception {
        UUID shelterId = UUID.randomUUID();
        when(shelters.getDetail(shelterId)).thenThrow(new NotificationException(NotificationException.Reason.FORBIDDEN));

        mvc.perform(get("/api/v1/shelters/" + shelterId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void aRejectedServiceSessionReturns401BeforeAnyNotificationDataIsRead() throws Exception {
        doThrow(new NotificationException(NotificationException.Reason.UNAUTHENTICATED))
                .when(authentication).requireActiveSession(session);

        mvc.perform(get("/notification-service/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(store);
    }

    @Test
    void anUnexpectedStoreFailureKeepsTheSharedSafe500BodyAndTraceId() throws Exception {
        String privateDetail = "test-private-fcm-token database-host=test-private-host";
        when(store.findSettings(session.memberId())).thenThrow(new IllegalStateException(privateDetail));

        var response = mvc.perform(get("/notification-service/settings"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse();

        var body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "traceId");
        assertThat(body.get("traceId").asString()).isNotBlank().isEqualTo(response.getHeader(TraceIdFilter.HEADER));
        assertThat(response.getContentAsString()).doesNotContain(privateDetail, "test-private-fcm-token", "test-private-host");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    /** Test-only controller: no notification HTTP endpoint is activated in production. */
    @TestComponent
    @RestController
    @Profile("notification-http-error-test")
    static class ErrorsProbe {
        @GetMapping("/notification-errors/{reason}")
        void fail(@PathVariable NotificationException.Reason reason) {
            throw new NotificationException(reason);
        }
    }

    /** Real service orchestration with test-only authentication; never a member HTTP API. */
    @TestComponent
    @RestController
    @Profile("notification-http-error-test")
    static class ServiceProbe {
        private final NotificationService notifications;
        private final MemberSession session;

        ServiceProbe(NotificationService notifications, MemberSession session) {
            this.notifications = notifications;
            this.session = session;
        }

        @GetMapping("/notification-service/settings")
        SettingsResponse settings() {
            return notifications.getSettings(session);
        }
    }
}

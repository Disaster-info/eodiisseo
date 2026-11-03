package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.auth.security.SessionAuthenticationFilter;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.exception.GlobalExceptionHandler;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.Device;
import com.disasterinfo.eodiisseo.notification.adapter.RepositoryNotificationAuthentication;
import com.disasterinfo.eodiisseo.notification.controller.NotificationController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real token verification and session filter with test-only repository/domain results, not production readiness. */
class NotificationControllerTest {
    private static final String TEST_KEY = "test-only-notification-signing-key-0123456789";
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private NotificationFixture f;
    private AuthRepository auth;
    private TokenService tokens;
    private String bearer;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        SecurityContextHolder.clearContext();
        f = new NotificationFixture(); auth = mock(AuthRepository.class);
        when(auth.findUsableSession(any(), any())).thenReturn(null);
        when(auth.findUsableSession(f.sessionId, f.member)).thenReturn(Map.of("session_id", f.sessionId));
        when(auth.listCurrentRoles(any())).thenReturn(List.of());
        tokens = new TokenService(TEST_KEY, "test-eodiisseo", "test-mobile", 900, Clock.fixed(NOW, ZoneOffset.UTC));
        bearer = "Bearer " + tokens.issue(f.member, f.sessionId, NOW.plusSeconds(3600));
        var authentication = new RepositoryNotificationAuthentication(new SessionAccessService(auth));
        mvc = mvc(new NotificationService(Optional.of(authentication), Optional.of(f.store),
                Optional.of(f.interests), Optional.of(f.severityReader), Optional.of(f.testCipher)));
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void allFourOperationsRejectAnonymousIdentityClaims() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/api/v1/notification-settings").param("memberId", f.member.toString()).param("sessionId", f.sessionId.toString()),
                patch("/api/v1/notification-settings").content("{}"),
                put("/api/v1/notification-devices/device-a").content("{\"fcmToken\":\"test-token\",\"platform\":\"IOS\"}"),
                delete("/api/v1/notification-devices/device-a"));
        for (var request : requests) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.traceId").isNotEmpty());
        }
        verifyNoInteractions(auth);
        assertThat(f.store.devices).isEmpty();
    }

    @Test void getUsesTheVerifiedPrincipalAndReturnsTheExistingUnwrappedDto() throws Exception {
        var response = mvc.perform(get("/api/v1/notification-settings").header("Authorization", bearer)
                        .param("memberId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.interestAreaIds[0]").value(f.area1.toString()))
                .andExpect(jsonPath("$.minimumSeverity").value("WARNING"))
                .andReturn().getResponse();
        var body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("enabled", "interestAreaIds", "minimumSeverity", "nightNotificationEnabled");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void patchPreservesOmittedFieldsAndDistinguishesAnExplicitNullMinimum() throws Exception {
        mvc.perform(patch("/api/v1/notification-settings").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.minimumSeverity").value("WARNING"))
                .andExpect(jsonPath("$.interestAreaIds.length()").value(2));
        mvc.perform(patch("/api/v1/notification-settings").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minimumSeverity\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minimumSeverity").isEmpty())
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(f.store.findSettings(f.member).minimumSeverityId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{\"enabled\":null}", "{\"enabled\":\"false\"}",
            "{\"nightNotificationEnabled\":null}", "{\"interestAreaIds\":null}", "{\"interestAreaIds\":[1]}",
            "{\"interestAreaIds\":[\"not-a-uuid\"]}", "{\"minimumSeverity\":5}",
            "{\"minimumSeverity\":\"UNKNOWN_OFFICIAL_GRADE\"}", "{\"memberId\":\"test-spoofed-owner\"}"})
    void invalidPatchInputNeverChangesSettings(String body) throws Exception {
        var previous = f.store.findSettings(f.member);
        mvc.perform(patch("/api/v1/notification-settings").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(f.store.findSettings(f.member)).isEqualTo(previous);
        assertThat(f.owned).allMatch(NotificationContracts.InterestArea::notifyEnabled);
    }

    @Test void anotherMembersAreaCannotBeSelected() throws Exception {
        mvc.perform(patch("/api/v1/notification-settings").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"interestAreaIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isNotFound());
        assertThat(f.owned).allMatch(NotificationContracts.InterestArea::notifyEnabled);
    }

    @Test void putAndDeleteReuseOwnedDeviceRegistrationAndNeverReturnThePushToken() throws Exception {
        String rawToken = "test-private-push-token";
        var response = mvc.perform(put("/api/v1/notification-devices/device-a").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + rawToken + "\",\"platform\":\"IOS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deviceId").value("device-a"))
                .andExpect(jsonPath("$.registered").value(true)).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(rawToken, "tokenHash", "tokenCiphertext");
        assertThat(f.store.findDevice("device-a").tokenHash()).isEqualTo(NotificationService.hash(rawToken));
        mvc.perform(delete("/api/v1/notification-devices/device-a").header("Authorization", bearer))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(f.store.findActiveDevices(f.member)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"fcmToken\":5,\"platform\":\"IOS\"}",
            "{\"fcmToken\":\"test-token\",\"platform\":\"WEB\"}",
            "{\"fcmToken\":\"test-token\",\"platform\":\"IOS\",\"memberId\":\"test-spoofed-owner\"}"})
    void invalidDeviceInputIsRejectedBeforeRegistration(String body) throws Exception {
        mvc.perform(put("/api/v1/notification-devices/device-a").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(f.store.devices).isEmpty();
    }

    @Test void foreignDeviceOwnershipIs409ForPutAnd403ForDeleteAndMissingDeviceIs404() throws Exception {
        f.device("device-a"); Device owned = f.store.findDevice("device-a");
        f.store.devices.put("device-a", new Device(owned.id(), UUID.randomUUID(), owned.deviceId(),
                owned.tokenHash(), owned.tokenCiphertext(), owned.platform(), true, owned.updatedAt()));
        mvc.perform(put("/api/v1/notification-devices/device-a").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"test-new-token\",\"platform\":\"IOS\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/notification-devices/device-a").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/notification-devices/missing").header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    @Test void anExpiredSignedTokenAndInvalidSignatureNeverReachSessionLookup() throws Exception {
        var past = new TokenService(TEST_KEY, "test-eodiisseo", "test-mobile", 1,
                Clock.fixed(NOW.minusSeconds(60), ZoneOffset.UTC));
        String expired = "Bearer " + past.issue(f.member, f.sessionId, NOW.plusSeconds(3600));
        clearInvocations(auth);
        mvc.perform(get("/api/v1/notification-settings").header("Authorization", expired)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notification-settings").header("Authorization", "Bearer test-invalid-signature"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
    }

    @Test void aRevokedPersistedSessionIsRejectedDespiteItsValidSignedBearer() throws Exception {
        when(auth.findUsableSession(f.sessionId, f.member)).thenReturn(null);
        mvc.perform(get("/api/v1/notification-settings").header("Authorization", bearer)).andExpect(status().isUnauthorized());
    }

    @Test void revocationAfterTheHttpFilterIsRecheckedByTheServiceFacade() throws Exception {
        when(auth.findUsableSession(f.sessionId, f.member)).thenReturn(Map.of("session_id", f.sessionId)).thenReturn(null);
        var previous = f.store.findSettings(f.member);
        mvc.perform(patch("/api/v1/notification-settings").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        assertThat(f.store.findSettings(f.member)).isEqualTo(previous);
    }

    @Test void anAuthenticationOutageIs503WithoutPrivateDetails() throws Exception {
        when(auth.findUsableSession(f.sessionId, f.member)).thenThrow(new IllegalStateException("test-private-auth-details"));
        var response = mvc.perform(get("/api/v1/notification-settings").header("Authorization", bearer))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("test-private-auth-details");
    }

    @Test void aMissingDomainAuthenticationPortDoesNotTrustTheVerifiedHttpPrincipalAlone() throws Exception {
        mvc = mvc(new NotificationService(Optional.empty(), Optional.of(f.store), Optional.of(f.interests),
                Optional.of(f.severityReader), Optional.of(f.testCipher)));
        mvc.perform(get("/api/v1/notification-settings").header("Authorization", bearer))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test void mappingsAreDisabledByDefaultAndRequireTheExplicitOwnedHttpFlag() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(NotificationService.class, f::service);
            context.register(NotificationController.class); context.refresh();
            assertThat(context.getBeansOfType(NotificationController.class)).isEmpty();
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-http-flag",
                    Map.of("eodiisseo.owned-http.enabled", "true")));
            context.registerBean(NotificationService.class, f::service);
            context.register(NotificationController.class); context.refresh();
            assertThat(context.getBeansOfType(NotificationController.class)).hasSize(1);
        }
    }

    private MockMvc mvc(NotificationService service) {
        return MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new NotificationHttpErrors())
                .addFilters(new TraceIdFilter(), new SessionAuthenticationFilter(tokens, auth, new ObjectMapper()))
                .build();
    }
}

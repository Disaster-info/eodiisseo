package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.ai.chat.ChatService;
import com.disasterinfo.eodiisseo.ai.chat.controller.ChatController;
import com.disasterinfo.eodiisseo.ai.http.AiExceptionHandler;
import com.disasterinfo.eodiisseo.ai.summary.SummaryService;
import com.disasterinfo.eodiisseo.ai.summary.controller.SummaryController;
import com.disasterinfo.eodiisseo.auth.config.AssignedSecurity;
import com.disasterinfo.eodiisseo.auth.config.AuthenticationPortsConfiguration;
import com.disasterinfo.eodiisseo.auth.controller.AuthController;
import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.exception.GlobalExceptionHandler;
import com.disasterinfo.eodiisseo.dashboard.controller.DashboardController;
import com.disasterinfo.eodiisseo.dashboard.http.OwnedHttpSecurityGuard;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.MemberSession;
import com.disasterinfo.eodiisseo.notification.NotificationHttpErrors;
import com.disasterinfo.eodiisseo.notification.NotificationService;
import com.disasterinfo.eodiisseo.notification.controller.NotificationController;
import com.disasterinfo.eodiisseo.weather.controller.WeatherController;
import com.disasterinfo.eodiisseo.weather.service.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actual production matcher, token filter, startup guard and all nine real Controller mappings.
 * Domain services and database results are test doubles; this verifies HTTP authentication rather
 * than claiming those fixtures implement the operational disaster/location/AI data dependencies.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = DashboardAssignedSecurityReadinessTest.Config.class)
@TestPropertySource(properties = {"JWT_SECRET=0123456789abcdef0123456789abcdef",
        "AUTH_BRIDGES_ENABLED=true", "eodiisseo.owned-http.enabled=true"})
class DashboardAssignedSecurityReadinessTest {
    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef";
    private static final UUID DISASTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String DISASTER = "/api/v1/disasters/" + DISASTER_ID;
    private static final String DEVICE_ID = "test-installation";
    private static final String DEVICE = "/api/v1/notification-devices/" + DEVICE_ID;

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({AssignedSecurity.class, AuthenticationPortsConfiguration.class, SessionAccessService.class,
            OwnedHttpSecurityGuard.class, AuthController.class, WeatherController.class,
            DashboardController.class, SummaryController.class, ChatController.class, NotificationController.class,
            DashboardHttpErrors.class, AiExceptionHandler.class, NotificationHttpErrors.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean OwnedDatabase database() { return mock(OwnedDatabase.class); }
        @Bean AuthRepository authRepository(OwnedDatabase database) { return new AuthRepository(database); }
        @Bean AuthService authService() { return mock(AuthService.class); }
        @Bean WeatherService weatherService() { return mock(WeatherService.class); }
        @Bean DashboardService dashboardService() { return mock(DashboardService.class); }
        @Bean DashboardDisasterQueryService disasterQueries() { return mock(DashboardDisasterQueryService.class); }
        @Bean SummaryService summaryService() { return mock(SummaryService.class); }
        @Bean ChatService chatService() { return mock(ChatService.class); }
        @Bean NotificationService notificationService() { return mock(NotificationService.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired WebApplicationContext context;
    @Autowired SecurityFilterChain assignedFeatures;
    @Autowired OwnedDatabase database;
    @Autowired TokenService tokens;
    @Autowired AuthService auth;
    @Autowired WeatherService weather;
    @Autowired DashboardService dashboard;
    @Autowired DashboardDisasterQueryService queries;
    @Autowired SummaryService summaries;
    @Autowired ChatService chat;
    @Autowired NotificationService notifications;
    private MockMvc mvc;
    private UUID memberId;
    private UUID sessionId;
    private String bearer;

    @BeforeEach
    void setUp() {
        reset(database, auth, weather, dashboard, queries, summaries, chat, notifications);
        memberId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        bearer = "Bearer " + tokens.issue(memberId, sessionId, Instant.now().plusSeconds(600));
        usableSession(true);
        when(database.rows(startsWith("select role"), eq(memberId))).thenReturn(List.of(Map.of("role", "USER")));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TraceIdFilter()).apply(springSecurity()).build();
    }

    static Stream<Arguments> ownedPathsAndMethods() {
        return Stream.of("/api/v1/dashboard", "/api/v1/disasters", DISASTER,
                        DISASTER + "/ai-summary", "/api/v1/ai/guidance", "/api/v1/notification-settings", DEVICE)
                .flatMap(path -> Stream.of("GET", "POST", "PUT", "PATCH", "DELETE")
                        .map(method -> Arguments.of(method, path)));
    }

    static Stream<Arguments> ownedEndpoints() {
        return Stream.of(Arguments.of("GET", "/api/v1/dashboard"), Arguments.of("GET", "/api/v1/disasters"),
                Arguments.of("GET", DISASTER), Arguments.of("GET", DISASTER + "/ai-summary"),
                Arguments.of("POST", "/api/v1/ai/guidance"), Arguments.of("GET", "/api/v1/notification-settings"),
                Arguments.of("PATCH", "/api/v1/notification-settings"), Arguments.of("PUT", DEVICE), Arguments.of("DELETE", DEVICE));
    }

    @ParameterizedTest(name = "production chain covers {0} {1}")
    @MethodSource("ownedPathsAndMethods")
    void productionChainProtectsOwnedPathsRegardlessOfHttpMethod(String method, String path) {
        assertThat(assignedFeatures.matches(request(method, path))).isTrue();
    }

    @ParameterizedTest(name = "real authentication protects {0} {1}")
    @MethodSource("ownedEndpoints")
    void everyOwnedControllerRejectsAnonymousInvalidExpiredAndRevokedCredentialsThenAcceptsVerifiedIdentity(
            String method, String path) throws Exception {
        mvc.perform(httpRequest(method, path)).andExpect(status().isUnauthorized());
        mvc.perform(httpRequest(method, path).header("Authorization", "Bearer invalid-service-token"))
                .andExpect(status().isUnauthorized());
        Instant old = Instant.now().minusSeconds(3600);
        String expired = new TokenService(TEST_SECRET, "eodiisseo", "eodiisseo-mobile", 900,
                Clock.fixed(old, ZoneOffset.UTC)).issue(memberId, sessionId, old.plusSeconds(900));
        mvc.perform(httpRequest(method, path).header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        usableSession(false);
        mvc.perform(httpRequest(method, path).header("Authorization", bearer)).andExpect(status().isUnauthorized());
        verifyNoInteractions(dashboard, queries, summaries, chat, notifications);
        usableSession(true);
        mvc.perform(httpRequest(method, path).header("Authorization", bearer))
                .andExpect(status().is(method.equals("DELETE") ? 204 : 200));
        verifyVerifiedPrincipal(method, path);
    }

    @Test
    void existingAuthAndWeatherPathsRemainCoveredByTheSameProductionChain() {
        assertThat(assignedFeatures.matches(request("POST", "/api/v1/auth/logout"))).isTrue();
        assertThat(assignedFeatures.matches(request("GET", "/api/v1/members/me"))).isTrue();
        assertThat(assignedFeatures.matches(request("GET", "/api/v1/weather/current"))).isTrue();
    }

    @Test
    void anonymousAuthAndWeatherRequestsRemainRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/weather/current")).andExpect(status().isUnauthorized());
        verifyNoInteractions(auth, weather);
    }

    @Test
    void signedBearerAndUsableSessionReachExistingAuthAndWeatherControllers() throws Exception {
        when(auth.me(new MemberPrincipal(memberId, sessionId))).thenReturn(Map.of("memberId", memberId));
        when(weather.current(memberId, 37.5, 127.0, null)).thenReturn(Map.of("weatherCode", "SUNNY"));
        mvc.perform(get("/api/v1/members/me").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/weather/current").param("latitude", "37.5").param("longitude", "127.0")
                .header("Authorization", bearer)).andExpect(status().isOk());
        verify(auth).me(new MemberPrincipal(memberId, sessionId));
        verify(weather).current(memberId, 37.5, 127.0, null);
    }

    @Test
    void revokedSessionIsRejectedForExistingAuthAndWeatherControllers() throws Exception {
        usableSession(false);
        mvc.perform(get("/api/v1/members/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/weather/current").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        verifyNoInteractions(auth, weather);
    }

    @Test
    void invalidSignatureIsRejectedBeforeAnySessionQuery() throws Exception {
        mvc.perform(get("/api/v1/weather/current").header("Authorization", "Bearer invalid-service-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(database, auth, weather);
    }

    private void usableSession(boolean usable) {
        when(database.one(startsWith("select s.session_id"), eq(sessionId), eq(memberId)))
                .thenReturn(usable ? Map.of("session_id", sessionId) : null);
    }

    private void verifyVerifiedPrincipal(String method, String path) {
        MemberSession session = new MemberSession(memberId, sessionId);
        if (path.equals("/api/v1/dashboard")) verify(dashboard).getDashboard(eq(memberId), eq(sessionId), any());
        else if (path.equals("/api/v1/disasters")) verify(queries).getDisasters(eq(memberId), eq(sessionId), any());
        else if (path.equals(DISASTER)) verify(queries).getDisaster(memberId, sessionId, DISASTER_ID);
        else if (path.endsWith("/ai-summary")) verify(summaries).getSummary(memberId, sessionId, DISASTER_ID);
        else if (path.equals("/api/v1/ai/guidance")) verify(chat).requestGuidance(eq(memberId), eq(sessionId), any());
        else if (path.equals("/api/v1/notification-settings") && method.equals("GET")) verify(notifications).getSettings(session);
        else if (path.equals("/api/v1/notification-settings")) verify(notifications).patchSettings(eq(session), any(), anySet());
        else if (method.equals("PUT")) verify(notifications).registerDevice(eq(session), eq(DEVICE_ID), any());
        else verify(notifications).unregisterDevice(session, DEVICE_ID);
    }

    private static MockHttpServletRequestBuilder httpRequest(String method, String path) {
        var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
        if (method.equals("POST")) builder.contentType("application/json").content("{\"question\":\"Fixture question\"}");
        else if (method.equals("PATCH")) builder.contentType("application/json").content("{\"enabled\":false}");
        else if (method.equals("PUT")) builder.contentType("application/json")
                .content("{\"fcmToken\":\"test-only-never-transmitted-token\",\"platform\":\"ANDROID\"}");
        return builder;
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}

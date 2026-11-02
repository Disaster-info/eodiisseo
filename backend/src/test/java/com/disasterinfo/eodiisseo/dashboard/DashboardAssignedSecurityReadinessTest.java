package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.auth.config.AssignedSecurity;
import com.disasterinfo.eodiisseo.auth.controller.AuthController;
import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Records the existing production chain's activation prerequisites, not a security fix.
 * Owned paths must stay unmapped until the auth owner extends this matcher.
 * HTTP methods are probes of matcher coverage, not declarations of API 5.2.1 contracts;
 * that specification is not available in the local source.
 * Real production token verification and filtering are used with test-only database results.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = DashboardAssignedSecurityReadinessTest.Config.class)
@TestPropertySource(properties = "JWT_SECRET=0123456789abcdef0123456789abcdef")
class DashboardAssignedSecurityReadinessTest {
    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({AssignedSecurity.class, AuthController.class, WeatherController.class})
    static class Config {
        @Bean OwnedDatabase database() { return mock(OwnedDatabase.class); }
        @Bean AuthRepository authRepository(OwnedDatabase database) { return new AuthRepository(database); }
        @Bean AuthService authService() { return mock(AuthService.class); }
        @Bean WeatherService weatherService() { return mock(WeatherService.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @Autowired WebApplicationContext context;
    @Autowired SecurityFilterChain assignedFeatures;
    @Autowired OwnedDatabase database;
    @Autowired TokenService tokens;
    @Autowired AuthService auth;
    @Autowired WeatherService weather;
    private MockMvc mvc;
    private UUID memberId;
    private UUID sessionId;
    private String bearer;

    @BeforeEach
    void setUp() {
        reset(database, auth, weather);
        memberId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        bearer = "Bearer " + tokens.issue(memberId, sessionId, Instant.now().plusSeconds(600));
        when(database.one(startsWith("select s.session_id"), eq(sessionId), eq(memberId)))
                .thenReturn(Map.of("session_id", sessionId));
        when(database.rows(startsWith("select role"), eq(memberId)))
                .thenReturn(List.of(Map.of("role", "USER")));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static Stream<Arguments> ownedPathsAndMethods() {
        String disaster = "/api/v1/disasters/00000000-0000-0000-0000-000000000001";
        String device = "/api/v1/notification-devices/test-installation";
        return Stream.of("/api/v1/dashboard", "/api/v1/disasters", disaster,
                        disaster + "/ai-summary", "/api/v1/ai/guidance",
                        "/api/v1/notification-settings", device)
                .flatMap(path -> Stream.of("GET", "POST", "PUT", "PATCH", "DELETE")
                        .map(method -> Arguments.of(method, path)));
    }

    @ParameterizedTest(name = "{0} {1} still needs the auth owner's matcher change")
    @MethodSource("ownedPathsAndMethods")
    void productionChainDoesNotYetProtectOwnedPathsRegardlessOfMethod(String method, String path) {
        assertThat(assignedFeatures.matches(request(method, path)))
                .as("This known gap blocks HTTP activation; update this assertion when auth owns the fix")
                .isFalse();
    }

    @Test
    void existingAuthAndWeatherPathsAreCoveredByTheSameProductionChain() {
        assertThat(assignedFeatures.matches(request("POST", "/api/v1/auth/logout"))).isTrue();
        assertThat(assignedFeatures.matches(request("GET", "/api/v1/members/me"))).isTrue();
        assertThat(assignedFeatures.matches(request("GET", "/api/v1/weather/current"))).isTrue();
    }

    @Test
    void anonymousAuthAndWeatherRequestsAreRejected() throws Exception {
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
    void revokedSessionIsRejectedDespiteAValidUnexpiredBearer() throws Exception {
        when(database.one(startsWith("select s.session_id"), eq(sessionId), eq(memberId))).thenReturn(null);
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

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}

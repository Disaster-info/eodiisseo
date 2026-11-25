package com.disasterinfo.eodiisseo.auth.config;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.exception.ApiErrors;
import com.disasterinfo.eodiisseo.ops.service.OpsService;
import com.disasterinfo.eodiisseo.ops.controller.OpsController;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.object;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ContextConfiguration(classes=AssignedSecurityTest.Config.class)
@TestPropertySource(properties={"JWT_SECRET=0123456789abcdef0123456789abcdef","app.cors.allowed-origin-patterns=http://localhost:*"})
class AssignedSecurityTest {
    @org.springframework.boot.test.context.TestConfiguration @EnableWebMvc @EnableWebSecurity
    @Import({com.disasterinfo.eodiisseo.place.PlaceController.class,com.disasterinfo.eodiisseo.place.PlaceRequestConfiguration.class,com.disasterinfo.eodiisseo.common.exception.GlobalExceptionHandler.class,AssignedSecurity.class,OpsController.class,ApiErrors.class,com.disasterinfo.eodiisseo.auth.controller.AuthController.class,com.disasterinfo.eodiisseo.common.config.WebConfig.class})
    static class Config {
        @Bean com.disasterinfo.eodiisseo.auth.repository.AuthRepository authRepository(OwnedDatabase db) { return new com.disasterinfo.eodiisseo.auth.repository.AuthRepository(db); }
        @Bean com.disasterinfo.eodiisseo.place.PlaceService places() { return mock(com.disasterinfo.eodiisseo.place.PlaceService.class); }
        @Bean com.disasterinfo.eodiisseo.route.service.RouteRequestLimiter placeLimiter() { return mock(com.disasterinfo.eodiisseo.route.service.RouteRequestLimiter.class); }
        @Bean OwnedDatabase database() { return mock(OwnedDatabase.class); }
        @Bean OpsService operations() { return mock(OpsService.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean com.disasterinfo.eodiisseo.auth.service.AuthService authentication() { return mock(com.disasterinfo.eodiisseo.auth.service.AuthService.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired OwnedDatabase db;
    @Autowired TokenService tokens;
    private MockMvc mvc;private UUID member,session;private String token;
    @BeforeEach void setUp() {
        reset(context.getBean(com.disasterinfo.eodiisseo.route.service.RouteRequestLimiter.class));
        reset(db);member=UUID.randomUUID();session=UUID.randomUUID();
        token=tokens.issue(member,session,Instant.now().plusSeconds(600));
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(db.one(startsWith("select s.session_id"),eq(session),eq(member))).thenReturn(object("session_id",session));
        when(db.rows(startsWith("select role"),eq(member))).thenReturn(List.of(object("role","USER")));
    }
    @Test void anonymousOperationsRequestIsUnauthorized() throws Exception { mvc.perform(get("/api/v1/ops/status")).andExpect(status().isUnauthorized()); }
    @Test void publicShelterAndRouteMappingsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/shelters/nearby").param("latitude","37.5").param("longitude","127")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/routes/shelters/"+UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
    @Test void placeMappingsRequireAuthentication() throws Exception {
        for(String path:List.of("search","geocode","reverse-geocode"))
            mvc.perform(get("/api/v1/places/"+path)).andExpect(status().isUnauthorized());
    }
    @Test void placeRequestsUseAuthenticatedMemberBudget() throws Exception {
        var limiter=context.getBean(com.disasterinfo.eodiisseo.route.service.RouteRequestLimiter.class);
        mvc.perform(get("/api/v1/places/search").param("keyword","역").header("Authorization","Bearer "+token))
            .andExpect(status().isOk());
        verify(limiter).checkPlaces(member);
        doThrow(new com.disasterinfo.eodiisseo.common.exception.BusinessException(com.disasterinfo.eodiisseo.common.exception.ErrorCode.RATE_LIMITED))
            .when(limiter).checkPlaces(member);
        mvc.perform(get("/api/v1/places/geocode").param("address","서울").header("Authorization","Bearer "+token))
            .andExpect(status().isTooManyRequests());
    }
    @Test void browserPreflightDoesNotRequireBearerCredentials() throws Exception {
        mvc.perform(options("/api/v1/ops/status").header("Origin","http://localhost:8081")
            .header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","authorization"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:8081"));
    }
    @Test void browserPreflightRejectsUntrustedOrigins() throws Exception {
        mvc.perform(options("/api/v1/ops/status").header("Origin","https://untrusted.invalid")
            .header("Access-Control-Request-Method","GET")).andExpect(status().isForbidden());
    }
    @Test void ordinaryMemberCannotUseOperations() throws Exception { mvc.perform(get("/api/v1/ops/status").header("Authorization","Bearer "+token)).andExpect(status().isForbidden()); }
    @Test void currentServerRoleAllowsOperations() throws Exception {
        when(db.rows(startsWith("select role"),eq(member))).thenReturn(List.of(object("role","USER"),object("role","OPS")));
        mvc.perform(get("/api/v1/ops/status").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void revokedSessionIsRejectedEvenWithUnexpiredValidSignature() throws Exception {
        when(db.one(startsWith("select s.session_id"),eq(session),eq(member))).thenReturn(null);
        mvc.perform(get("/api/v1/ops/status").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void refreshCanUseItsCredentialEvenWhenTheAppSendsAnExpiredAccessHeader() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header("Authorization","Bearer expired-access-token")
            .contentType("application/json").content("{\"refreshToken\":\"refresh-credential\"}")).andExpect(status().isOk());
        verify(context.getBean(com.disasterinfo.eodiisseo.auth.service.AuthService.class)).refresh("refresh-credential");
    }
    // M5: 웹(X-Auth-Transport: cookie)은 refresh 토큰을 HttpOnly 쿠키로만 주고받고 본문에는 넣지 않음
    @Test void browserRefreshReadsTheHttpOnlyCookieAndRotatesItWithoutExposingTheToken() throws Exception {
        var service=context.getBean(com.disasterinfo.eodiisseo.auth.service.AuthService.class);
        org.mockito.Mockito.when(service.refresh("cookie-credential")).thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of(
            "accessToken","new-access","refreshToken","rotated-credential","refreshExpiresAt",java.time.Instant.now().plusSeconds(3600))));
        mvc.perform(post("/api/v1/auth/refresh").header("X-Auth-Transport","cookie").cookie(new jakarta.servlet.http.Cookie("eodiisseo_refresh","cookie-credential")))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accessToken").value("new-access"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.refreshToken").doesNotExist())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Set-Cookie",
                org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("eodiisseo_refresh=rotated-credential"),
                    org.hamcrest.Matchers.containsString("HttpOnly"),org.hamcrest.Matchers.containsString("Path=/api/v1/auth"))));
    }
    // 쿠키 방식 표시 헤더가 없으면 쿠키를 읽지 않음 (다른 사이트가 보낸 위조 요청에 쿠키만 실려 와도 쓰지 않음)
    @Test void refreshCookieIsIgnoredWithoutTheBrowserTransportHeader() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").cookie(new jakarta.servlet.http.Cookie("eodiisseo_refresh","forged-credential")))
            .andExpect(status().isBadRequest());
        verify(context.getBean(com.disasterinfo.eodiisseo.auth.service.AuthService.class),org.mockito.Mockito.never()).refresh("forged-credential");
    }
    @Test void browserLogoutRevokesByCookieAndClearsIt() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("X-Auth-Transport","cookie").cookie(new jakarta.servlet.http.Cookie("eodiisseo_refresh","cookie-credential")))
            .andExpect(status().isNoContent())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Set-Cookie",
                org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("eodiisseo_refresh="),org.hamcrest.Matchers.containsString("Max-Age=0"))));
        verify(context.getBean(com.disasterinfo.eodiisseo.auth.service.AuthService.class)).logoutByRefresh("cookie-credential");
    }
    @Test void trustedBrowserOriginMayCarryCredentials() throws Exception {
        mvc.perform(options("/api/v1/auth/refresh").header("Origin","http://localhost:8081")
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","x-auth-transport"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Credentials","true"));
    }
}

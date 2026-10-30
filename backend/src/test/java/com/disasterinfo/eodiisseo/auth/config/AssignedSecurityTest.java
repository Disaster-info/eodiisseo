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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ContextConfiguration(classes=AssignedSecurityTest.Config.class)
@TestPropertySource(properties="JWT_SECRET=0123456789abcdef0123456789abcdef")
class AssignedSecurityTest {
    @org.springframework.boot.test.context.TestConfiguration @EnableWebMvc @EnableWebSecurity
    @Import({AssignedSecurity.class,OpsController.class,ApiErrors.class,com.disasterinfo.eodiisseo.auth.controller.AuthController.class})
    static class Config {
        @Bean com.disasterinfo.eodiisseo.auth.repository.AuthRepository authRepository(OwnedDatabase db) { return new com.disasterinfo.eodiisseo.auth.repository.AuthRepository(db); }
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
        reset(db);member=UUID.randomUUID();session=UUID.randomUUID();
        token=tokens.issue(member,session,Instant.now().plusSeconds(600));
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(db.one(startsWith("select s.session_id"),eq(session),eq(member))).thenReturn(object("session_id",session));
        when(db.rows(startsWith("select role"),eq(member))).thenReturn(List.of(object("role","USER")));
    }
    @Test void anonymousOperationsRequestIsUnauthorized() throws Exception { mvc.perform(get("/api/v1/ops/status")).andExpect(status().isUnauthorized()); }
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
}

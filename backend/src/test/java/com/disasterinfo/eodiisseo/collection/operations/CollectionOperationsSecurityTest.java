package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.auth.config.AssignedSecurity;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.repository.*;
import com.disasterinfo.eodiisseo.auth.exception.ApiErrors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.object;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ContextConfiguration(classes=CollectionOperationsSecurityTest.Config.class)
@ActiveProfiles("collection4a-security-only")
@TestPropertySource(properties={"JWT_SECRET=0123456789abcdef0123456789abcdef","eodiisseo.collection.operations.admin-enabled=true"})
class CollectionOperationsSecurityTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @Profile("collection4a-security-only") @EnableWebMvc @EnableWebSecurity
    @Import({AssignedSecurity.class,CollectionOperationsController.class,ApiErrors.class})
    static class Config {
        @Bean AuthRepository authRepository(OwnedDatabase db){return new AuthRepository(db);}
        @Bean OwnedDatabase db(){return mock(OwnedDatabase.class);}
        @Bean CollectionOperationsService service(){return mock(CollectionOperationsService.class);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
    }
    @Autowired WebApplicationContext app;@Autowired OwnedDatabase db;@Autowired CollectionOperationsService service;@Autowired TokenService tokens;
    MockMvc mvc;UUID member,session;String token;
    @BeforeEach void setup(){reset(db,service);member=UUID.randomUUID();session=UUID.randomUUID();token=tokens.issue(member,session,Instant.now().plusSeconds(600));mvc=MockMvcBuilders.webAppContextSetup(app).apply(springSecurity()).build();when(db.one(startsWith("select s.session_id"),eq(session),eq(member))).thenReturn(object("session_id",session));when(db.rows(startsWith("select role"),eq(member))).thenReturn(List.of(object("role","OPS")));when(service.investigate(any())).thenReturn(List.of());}
    @Test void anonymousCannotInspectOrResolve()throws Exception{mvc.perform(get("/api/v1/ops/collection-administration")).andExpect(status().isUnauthorized());verifyNoInteractions(service);}
    @Test void ordinaryMemberCannotInspect()throws Exception{when(db.rows(startsWith("select role"),eq(member))).thenReturn(List.of(object("role","USER")));mvc.perform(get("/api/v1/ops/collection-administration").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());verifyNoInteractions(service);}
    @Test void currentOpsSessionCanInspect()throws Exception{mvc.perform(get("/api/v1/ops/collection-administration").header("Authorization","Bearer "+token)).andExpect(status().isOk());verify(service).investigate(new com.disasterinfo.eodiisseo.auth.security.MemberPrincipal(member,session));}
    @Test void revokedSessionIsRejectedBeforeController()throws Exception{when(db.one(startsWith("select s.session_id"),eq(session),eq(member))).thenReturn(null);mvc.perform(get("/api/v1/ops/collection-administration").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());verifyNoInteractions(service);}
}
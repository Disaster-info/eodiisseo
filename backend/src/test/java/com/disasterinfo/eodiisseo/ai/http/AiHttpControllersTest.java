package com.disasterinfo.eodiisseo.ai.http;

import com.disasterinfo.eodiisseo.ai.chat.ChatService;
import com.disasterinfo.eodiisseo.ai.chat.controller.ChatController;
import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerStore;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatPromptPrivacyPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatRetentionPolicy;
import com.disasterinfo.eodiisseo.ai.summary.OfficialRevision;
import com.disasterinfo.eodiisseo.ai.summary.SummaryService;
import com.disasterinfo.eodiisseo.ai.summary.SummaryState;
import com.disasterinfo.eodiisseo.ai.summary.SummaryStatus;
import com.disasterinfo.eodiisseo.ai.summary.controller.SummaryController;
import com.disasterinfo.eodiisseo.ai.summary.port.SummarySourceReader;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryStore;
import com.disasterinfo.eodiisseo.auth.config.AuthenticationPortsConfiguration;
import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.auth.security.SessionAuthenticationFilter;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.exception.GlobalExceptionHandler;
import com.disasterinfo.eodiisseo.dashboard.http.OwnedHttpSecurityGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Actual controllers, services, authentication bridges and token/session filter. The broader chain,
 * repository data and retention policy are test fixtures; production matcher readiness is separate.
 * No production RAG approval, privacy consent, database persistence or model call is simulated as real.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = AiHttpControllersTest.Config.class)
@TestPropertySource(properties = {"eodiisseo.owned-http.enabled=true", "AUTH_BRIDGES_ENABLED=true"})
class AiHttpControllersTest {
    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final UUID MEMBER = UUID.fromString("0e2f44c6-a1b6-4fb5-b472-28385f8a7801");
    private static final UUID SESSION = UUID.fromString("0e2f44c6-a1b6-4fb5-b472-28385f8a7802");
    private static final UUID DISASTER = UUID.fromString("0e2f44c6-a1b6-4fb5-b472-28385f8a7803");
    private static final UUID REVISION = UUID.fromString("0e2f44c6-a1b6-4fb5-b472-28385f8a7804");
    private static final String SUMMARY_PATH = "/api/v1/disasters/" + DISASTER + "/ai-summary";
    private static final String CHAT_PATH = "/api/v1/ai/guidance";

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SummaryController.class, ChatController.class, SummaryService.class, ChatService.class,
            AuthenticationPortsConfiguration.class, SessionAccessService.class,
            OwnedHttpSecurityGuard.class, AiExceptionHandler.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean AuthRepository repository() { return mock(AuthRepository.class); }
        @Bean SummarySourceReader sources() { return mock(SummarySourceReader.class); }
        @Bean SummaryStore summaries() { return mock(SummaryStore.class); }
        @Bean ChatAnswerStore answers() { return mock(ChatAnswerStore.class); }
        @Bean ChatPromptPrivacyPort privacy() { return mock(ChatPromptPrivacyPort.class); }
        @Bean ChatOfficialResourceSearchPort resources() { return mock(ChatOfficialResourceSearchPort.class); }
        @Bean ChatAnswerGenerationPort generation() { return mock(ChatAnswerGenerationPort.class); }
        @Bean ChatRetentionPolicy testOnlyRetentionPolicy() {
            return new ChatRetentionPolicy() {
                public boolean isApproved() { return true; }
                public Instant approvedExpiresAt(Instant requestedAt, Instant completedAt) {
                    return completedAt.plus(Duration.ofDays(90));
                }
            };
        }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean TokenService tokens() { return tokenService(NOW); }
        // Test-only chain: it deliberately does not modify or import AssignedSecurity.
        @Bean SecurityFilterChain testOnlyAiSecurity(HttpSecurity http, TokenService tokens,
                                                    AuthRepository repository, ObjectMapper json) throws Exception {
            return http.securityMatcher("/api/v1/**")
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
                        response.setStatus(401);
                        response.setContentType("application/json");
                        response.getWriter().write(json.writeValueAsString(Map.of("code", "UNAUTHORIZED",
                                "message", "Authentication is required.", "traceId", "test-fixture")));
                    }))
                    .addFilterBefore(new SessionAuthenticationFilter(tokens, repository, json),
                            UsernamePasswordAuthenticationFilter.class).build();
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired AuthRepository repository;
    @Autowired SummarySourceReader sources;
    @Autowired SummaryStore summaries;
    @Autowired ChatAnswerStore answers;
    @Autowired ChatPromptPrivacyPort privacy;
    @Autowired ChatOfficialResourceSearchPort resources;
    @Autowired ChatAnswerGenerationPort generation;
    @Autowired TokenService tokens;
    private MockMvc mvc;
    private String bearer;

    @BeforeEach
    void setUp() {
        reset(repository, sources, summaries, answers, privacy, resources, generation);
        when(repository.findUsableSession(SESSION, MEMBER)).thenReturn(Map.of("session_id", SESSION));
        when(repository.lockUsableSession(SESSION, MEMBER)).thenReturn(Map.of("session_id", SESSION));
        when(repository.listCurrentRoles(MEMBER)).thenReturn(List.of(Map.of("role", "USER")));
        when(answers.isAvailable()).thenReturn(true);
        when(privacy.minimizeLocally(any())).thenReturn(Optional.empty());
        doCallRealMethod().when(privacy).minimizeLocally(any(UUID.class),anyString());
        var revision = new OfficialRevision(DISASTER, UUID.randomUUID(), REVISION, 1, "HEAVY_RAIN", "Fixture region",
                OfficialRevision.DisasterStatus.ONGOING, null, "Synthetic test source", NOW, OfficialRevision.Origin.OFFICIAL);
        when(sources.findDisaster(DISASTER)).thenReturn(Optional.of(new SummarySourceReader.Source(
                DISASTER, OfficialRevision.Origin.OFFICIAL, Optional.of(revision))));
        when(sources.isCurrentRevision(REVISION)).thenReturn(true);
        when(summaries.findByRevision(REVISION)).thenReturn(Optional.empty());
        bearer = "Bearer " + tokens.issue(MEMBER, SESSION, NOW.plusSeconds(900));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TraceIdFilter())
                .apply(springSecurity()).build();
    }

    @Test
    void bothControllersAreAbsentUnlessExplicitlyEnabled() {
        try (var inactive = new AnnotationConfigApplicationContext()) {
            inactive.registerBean(SummaryService.class, () -> mock(SummaryService.class));
            inactive.registerBean(ChatService.class, () -> mock(ChatService.class));
            inactive.register(SummaryController.class, ChatController.class);
            inactive.refresh();
            assertThat(inactive.getBeansOfType(SummaryController.class)).isEmpty();
            assertThat(inactive.getBeansOfType(ChatController.class)).isEmpty();
        }
    }

    @Test
    void anonymousRequestsAndCallerSuppliedIdsDoNotReachDomainDependencies() throws Exception {
        mvc.perform(get(SUMMARY_PATH).param("memberId", MEMBER.toString()).param("sessionId", SESSION.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(CHAT_PATH).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Fixture question\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sources, summaries, answers);
    }

    @Test
    void invalidSignaturesAndExpiredTokensAreRejectedBeforeSourceAccess() throws Exception {
        mvc.perform(get(SUMMARY_PATH).header("Authorization", "Bearer invalid-token")).andExpect(status().isUnauthorized());
        String expired = tokenService(NOW.minusSeconds(3600)).issue(MEMBER, SESSION, NOW.minusSeconds(1800));
        mvc.perform(post(CHAT_PATH).header("Authorization", "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Fixture question\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sources, summaries, answers);
    }

    @Test
    void revokedExpiredOrInactivePersistedSessionsAreDeniedDespiteAValidSignature() throws Exception {
        when(repository.findUsableSession(SESSION, MEMBER)).thenReturn(null);
        mvc.perform(get(SUMMARY_PATH).header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(post(CHAT_PATH).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Fixture question\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sources, summaries, answers);
    }

    @Test
    void authenticationRepositoryFailureDoesNotGrantAccess() throws Exception {
        when(repository.findUsableSession(SESSION, MEMBER)).thenThrow(new IllegalStateException("private auth details"));
        var result = mvc.perform(get(SUMMARY_PATH).header("Authorization", bearer))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private auth details");
        verifyNoInteractions(sources, summaries, answers);
    }

    @Test
    void wrongPrincipalTypeIsRejectedByBothControllers() throws Exception {
        var unrelated = UsernamePasswordAuthenticationToken.authenticated("not-a-member-principal", null, List.of());
        mvc.perform(get(SUMMARY_PATH).with(authentication(unrelated)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post(CHAT_PATH).with(authentication(unrelated)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Fixture question\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(sources, summaries, answers);
    }

    @ParameterizedTest
    @EnumSource(SummaryStatus.class)
    void summaryHttpPreservesEveryExistingStateAndOnlyExposesCompletedContent(SummaryStatus status) throws Exception {
        when(summaries.findByRevision(REVISION)).thenReturn(Optional.of(new SummaryState(UUID.randomUUID(),
                DISASTER, REVISION, status, status == SummaryStatus.COMPLETED ? "Fixture summary" : null,
                status == SummaryStatus.COMPLETED ? "test-model" : null, 0, NOW,
                status == SummaryStatus.COMPLETED ? NOW : null)));
        var result = mvc.perform(get(SUMMARY_PATH).header("Authorization", bearer)
                        .param("memberId", UUID.randomUUID().toString()).param("sessionId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disasterId").value(DISASTER.toString()))
                .andExpect(jsonPath("$.status").value(status.name())).andReturn();
        var body = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("disasterId", "status", "summary", "generatedAt");
        if (status == SummaryStatus.COMPLETED) assertThat(body.get("summary").asString()).isEqualTo("Fixture summary");
        else {
            assertThat(body.get("summary").isNull()).isTrue();
            assertThat(body.get("generatedAt").isNull()).isTrue();
        }
        verify(repository, times(2)).findUsableSession(SESSION, MEMBER);
        traceMatches(result);
    }

    @Test
    void sourceChangeDuringReadNeverExposesThePreviousSummaryAsCurrent() throws Exception {
        when(summaries.findByRevision(REVISION)).thenReturn(Optional.of(new SummaryState(UUID.randomUUID(), DISASTER,
                REVISION, SummaryStatus.COMPLETED, "Old fixture summary", "test-model", 0, NOW, NOW)));
        when(sources.isCurrentRevision(REVISION)).thenReturn(false);
        mvc.perform(get(SUMMARY_PATH).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.summary").isEmpty()).andExpect(jsonPath("$.generatedAt").isEmpty());
    }

    @Test
    void nonexistentSourceAndInvalidDisasterUuidRetainTheirHttpStatuses() throws Exception {
        when(sources.findDisaster(DISASTER)).thenReturn(Optional.empty());
        traceMatches(mvc.perform(get(SUMMARY_PATH).header("Authorization", bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND")).andReturn());
        for (String invalidId : List.of("not-a-uuid", "1-1-1-1-1")) {
            mvc.perform(get("/api/v1/disasters/" + invalidId + "/ai-summary").header("Authorization", bearer))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Test
    void chatRejectsBlankMissingAndMalformedQuestionsWithoutSavingOrApprovingPrivacy() throws Exception {
        for (String body : List.of("{\"question\":\"  \"}", "{}", "{bad-json")) {
            mvc.perform(post(CHAT_PATH).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verify(answers, never()).save(any(), any());
        verifyNoInteractions(privacy, resources, generation);
    }

    @Test
    void privacyRefusalKeepsTheExistingFallbackAndSavesOnlyForTheVerifiedMember() throws Exception {
        var result = mvc.perform(post(CHAT_PATH).header("Authorization", bearer)
                        .param("memberId", UUID.randomUUID().toString()).param("sessionId", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Fixture question\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answerType").value("FALLBACK"))
                .andExpect(jsonPath("$.answer").value("공식기관에서 제공하는 재난 행동요령을 확인하세요."))
                .andExpect(jsonPath("$.sources").isEmpty()).andReturn();
        var saved = ArgumentCaptor.forClass(ChatAnswer.class);
        verify(answers).save(saved.capture(), eq(List.of()));
        assertThat(saved.getValue().getMemberId()).isEqualTo(MEMBER);
        verify(privacy).minimizeLocally("Fixture question");
        verifyNoInteractions(resources, generation);
        verify(repository).lockMember(MEMBER);
        verify(repository).lockUsableSession(SESSION, MEMBER);
        var body = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("answerType", "answer", "sources", "generatedAt");
        traceMatches(result);
    }

    @Test
    void unavailableAnswerPersistenceFailsExplicitlyWithoutAnInventedSuccess() throws Exception {
        when(answers.isAvailable()).thenReturn(false);
        mvc.perform(post(CHAT_PATH).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Fixture question\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        verify(answers, never()).save(any(), any());
    }

    private static TokenService tokenService(Instant now) {
        return new TokenService(TEST_SECRET, "ai-http-test", "ai-http-test", 900, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void traceMatches(MvcResult result) throws Exception {
        var response = result.getResponse();
        String trace = response.getHeader(TraceIdFilter.HEADER);
        assertThat(trace).isNotBlank();
        if (response.getStatus() >= 400) {
            var body = JsonMapper.builder().build().readTree(response.getContentAsString());
            assertThat(body.get("traceId").asString()).isEqualTo(trace);
        }
    }
}

package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.auth.config.AuthenticationPortsConfiguration;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.Authentication;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.MemberSession;
import com.disasterinfo.eodiisseo.notification.NotificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RepositoryNotificationAuthenticationTest {
    private final SessionAccessService sessions = mock(SessionAccessService.class);
    private final RepositoryNotificationAuthentication adapter = new RepositoryNotificationAuthentication(sessions);
    private final UUID member = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    @Test void validSessionDelegatesToTheAuthOwnersService() {
        adapter.requireActiveSession(new MemberSession(member, session));
        verify(sessions).requireActiveSession(member, session);
        verifyNoMoreInteractions(sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "revoked", "withdrawn", "different-owner"})
    void anUnusableSessionCannotBeAcceptedWhenTheAuthServiceRejectsIt(String state) {
        doThrow(ApiProblem.unauthorized()).when(sessions).requireActiveSession(member, session);
        assertThatThrownBy(() -> adapter.requireActiveSession(new MemberSession(member, session)))
                .as(state)
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.UNAUTHENTICATED));
        verify(sessions).requireActiveSession(member, session);
    }

    @Test void nullIdentityNeverCallsTheAuthService() {
        assertThatThrownBy(() -> adapter.requireActiveSession(null))
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.UNAUTHENTICATED));
        assertThatThrownBy(() -> adapter.requireActiveSession(new MemberSession(member, null)))
                .isInstanceOf(NotificationException.class);
        assertThatThrownBy(() -> adapter.requireActiveSession(new MemberSession(null, session)))
                .isInstanceOf(NotificationException.class);
        verifyNoInteractions(sessions);
    }

    @Test void workerMemberStateDelegatesToTheSameAuthService() {
        when(sessions.isActiveMember(member)).thenReturn(true, false);
        assertThat(adapter.isActiveMember(member)).isTrue();
        assertThat(adapter.isActiveMember(member)).isFalse();
        assertThat(adapter.isActiveMember(null)).isFalse();
        verify(sessions, times(2)).isActiveMember(member);
        verifyNoMoreInteractions(sessions);
    }

    @Test void authOutagesFailClosedWithoutLeakingDetails() {
        doThrow(new IllegalStateException("test-private-jdbc-details"))
                .when(sessions).requireActiveSession(member, session);
        assertThatThrownBy(() -> adapter.requireActiveSession(new MemberSession(member, session)))
                .isInstanceOfSatisfying(NotificationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEPENDENCY_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("test-private-jdbc-details");
                });
        when(sessions.isActiveMember(member)).thenThrow(new IllegalStateException("test-private-jdbc-details"));
        assertThatThrownBy(() -> adapter.isActiveMember(member))
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEPENDENCY_UNAVAILABLE));
    }

    @Test void aNonAuthenticationApiFailureCannotBecomeAnUnauthorizedResult() {
        doThrow(new ApiProblem(503, "AUTHENTICATION_UNAVAILABLE", "test-private-details"))
                .when(sessions).requireActiveSession(member, session);
        assertThatThrownBy(() -> adapter.requireActiveSession(new MemberSession(member, session)))
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEPENDENCY_UNAVAILABLE));
    }

    @ParameterizedTest
    @CsvSource({"false,false,0", "true,false,1", "false,true,1", "true,true,1"})
    void flagsSelectOneImplementationAndTheSharedBridgeTakesPrecedence(
            boolean sharedBridge, boolean legacyAdapter, int expectedCount) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-auth-flags", Map.of(
                    "AUTH_BRIDGES_ENABLED", String.valueOf(sharedBridge),
                    "eodiisseo.notification.auth.enabled", String.valueOf(legacyAdapter))));
            context.registerBean(SessionAccessService.class, () -> sessions);
            context.register(AuthenticationPortsConfiguration.class, RepositoryNotificationAuthentication.class);
            context.refresh();
            assertThat(context.getBeansOfType(Authentication.class)).hasSize(expectedCount);
            if (expectedCount == 1) {
                Authentication authentication = context.getBean(Authentication.class);
                assertThat(authentication instanceof RepositoryNotificationAuthentication).isEqualTo(!sharedBridge);
                authentication.requireActiveSession(new MemberSession(member, session));
                verify(sessions).requireActiveSession(member, session);
            }
        }
    }

    @Test void theLegacyFlagStillWorksWhenTheSharedFlagIsOmitted() {
        try (var context = new AnnotationConfigApplicationContext()) {
            // This case requires an absent flag, independently of the enclosing integration process.
            context.setEnvironment(new org.springframework.mock.env.MockEnvironment()
                    .withProperty("eodiisseo.notification.auth.enabled", "true"));
            assertThat(context.getEnvironment().containsProperty("AUTH_BRIDGES_ENABLED")).isFalse();
            context.registerBean(SessionAccessService.class, () -> sessions);
            context.register(RepositoryNotificationAuthentication.class);
            context.refresh();
            assertThat(context.getBeansOfType(Authentication.class)).hasSize(1);
        }
    }

    @Test void enablingTheLegacyAdapterWithoutTheAuthServiceFailsStartup() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-auth-flags", Map.of(
                    "AUTH_BRIDGES_ENABLED", "false", "eodiisseo.notification.auth.enabled", "true")));
            context.register(RepositoryNotificationAuthentication.class);
            assertThatThrownBy(context::refresh)
                    .isInstanceOf(org.springframework.beans.factory.UnsatisfiedDependencyException.class);
        }
    }
}

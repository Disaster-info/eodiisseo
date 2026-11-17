package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryAuthPort;
import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.auth.security.SessionAuthenticationFilter;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.dashboard.http.OwnedHttpSecurityGuard;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import com.disasterinfo.eodiisseo.notification.NotificationContracts;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OwnedHttpSecurityGuardTest {
    @Test
    void allContractsRequireTheExistingSessionFilterAndAnonymousDenial() {
        var factory = factory(List.of(protectedChain(request -> true)));
        assertThatCode(() -> guard(factory).afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    @Test
    void anUnmatchedPatchCannotBeHiddenByOtherProtectedMethods() {
        var factory = factory(List.of(protectedChain(request -> !"PATCH".equals(request.getMethod()))));
        assertThatThrownBy(() -> guard(factory).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PATCH /api/v1/notification-settings");
    }

    @Test
    void anEarlierUnprotectedChainCannotBeHiddenByALaterProtectedChain() {
        var factory = factory(List.of(new DefaultSecurityFilterChain(request -> true), protectedChain(request -> true)));
        assertThatThrownBy(() -> guard(factory).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bearer/session protection");
    }

    @Test
    void havingFiltersWithoutAnAuthenticatedRuleDoesNotCountAsProtection() {
        var permitted = new AuthorizationFilter((authentication, request) -> new AuthorizationDecision(true));
        var factory = factory(List.of(new DefaultSecurityFilterChain(request -> true, sessionFilter(), permitted)));
        assertThatThrownBy(() -> guard(factory).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("anonymous access is not denied");
    }

    @Test
    void abstainingAuthorizationDoesNotCountAsProtection() {
        var abstaining = new AuthorizationFilter((authentication, request) -> null);
        var factory = factory(List.of(new DefaultSecurityFilterChain(request -> true, sessionFilter(), abstaining)));
        assertThatThrownBy(() -> guard(factory).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("anonymous access is not denied");
    }

    @Test
    void aMissingOrDuplicateAuthenticationBridgeRefusesActivation() {
        var missing = factory(List.of(protectedChain(request -> true)));
        missing.destroySingleton("notificationAuth");
        assertThatThrownBy(() -> guard(missing).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one Authentication");
        var duplicate = factory(List.of(protectedChain(request -> true)));
        duplicate.registerSingleton("anotherNotificationAuth", mock(NotificationContracts.Authentication.class));
        assertThatThrownBy(() -> guard(duplicate).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one Authentication");
    }

    private static DefaultListableBeanFactory factory(List<SecurityFilterChain> chains) {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("security", new FilterChainProxy(chains));
        factory.registerSingleton("dashboardAuth", mock(DashboardSessionVerifier.class));
        factory.registerSingleton("summaryAuth", mock(SummaryAuthPort.class));
        factory.registerSingleton("chatAuth", mock(ChatAuthenticationPort.class));
        factory.registerSingleton("notificationAuth", mock(NotificationContracts.Authentication.class));
        return factory;
    }

    private static OwnedHttpSecurityGuard guard(DefaultListableBeanFactory factory) {
        return new OwnedHttpSecurityGuard(factory.getBeanProvider(FilterChainProxy.class), factory);
    }

    private static SecurityFilterChain protectedChain(RequestMatcher matcher) {
        return new DefaultSecurityFilterChain(matcher, sessionFilter(),
                new AuthorizationFilter(AuthenticatedAuthorizationManager.authenticated()));
    }

    private static Filter sessionFilter() {
        return new SessionAuthenticationFilter(mock(TokenService.class), mock(AuthRepository.class), new ObjectMapper());
    }
}
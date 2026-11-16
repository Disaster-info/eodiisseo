package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.auth.client.SocialIdentityVerifier;
import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Keeps the current auth service boundary explicit while the real session facade is missing.
 * A profile lookup or an authenticated context flag does not establish a usable service session.
 */
class DashboardAuthFacadeReadinessTest {
    private static final UUID MEMBER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID UNKNOWN_SESSION = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void profileLookupIsNotAReplacementForSessionValidation() {
        AuthRepository repository = mock(AuthRepository.class);
        when(repository.findActiveMember(MEMBER)).thenReturn(Map.of("display_name", "Test member"));
        when(repository.listLinkedProviders(MEMBER)).thenReturn(List.of());
        when(repository.listRoles(MEMBER)).thenReturn(List.of());
        AuthService service = new AuthService(repository, mock(TokenService.class),
                mock(SocialIdentityVerifier.class), Clock.systemUTC(), mock(PlatformTransactionManager.class), 1209600);

        // No usable-session result exists for this principal, yet the profile method succeeds.
        assertThat(service.me(new MemberPrincipal(MEMBER, UNKNOWN_SESSION)))
                .containsEntry("memberId", MEMBER);

        verify(repository).findActiveMember(MEMBER);
        verify(repository, never()).findUsableSession(any(), any());
    }

    @Test
    void authenticatedContextWithoutRealSessionPortCannotReachDashboardData() {
        AtomicInteger readerCalls = new AtomicInteger();
        DashboardService dashboard = new DashboardService(Optional.empty(),
                Optional.of((member, criteria, base) -> {
                    readerCalls.incrementAndGet();
                    return List.of();
                }), Optional.empty());
        var previous = SecurityContextHolder.getContext();
        var fabricated = SecurityContextHolder.createEmptyContext();
        fabricated.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new MemberPrincipal(MEMBER, UNKNOWN_SESSION), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(fabricated);
        try {
            assertThat(fabricated.getAuthentication().isAuthenticated()).isTrue();
            assertThatThrownBy(() -> dashboard.getDashboard(MEMBER, UNKNOWN_SESSION,
                    new DashboardQuery(null, null, null)))
                    .isInstanceOfSatisfying(DashboardException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(DashboardException.Reason.DEPENDENCY_UNAVAILABLE));
            assertThat(readerCalls).hasValue(0);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}

package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.MemberSession;
import com.disasterinfo.eodiisseo.notification.NotificationException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RepositoryNotificationAuthenticationTest {
    private final AuthRepository repository = mock(AuthRepository.class);
    private final RepositoryNotificationAuthentication adapter = new RepositoryNotificationAuthentication(repository);
    private final UUID member = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    @Test void validSessionDelegatesToExistingAuthRepository() {
        when(repository.findUsableSession(session, member)).thenReturn(Map.of("session_id", session));

        adapter.requireActiveSession(new MemberSession(member, session));

        verify(repository).findUsableSession(session, member);
        verifyNoMoreInteractions(repository);
    }

    @Test void missingOrRevokedSessionIsRejected() {
        // Mockito returns an empty Map by default for unstubbed Map methods, not null.
        // The real repository returns null when no usable session row exists.
        when(repository.findUsableSession(session, member)).thenReturn(null);
        assertThatThrownBy(() -> adapter.requireActiveSession(new MemberSession(member, session)))
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.UNAUTHENTICATED));
        verify(repository).findUsableSession(session, member);
    }

    @Test void nullIdentityNeverQueriesDatabase() {
        assertThatThrownBy(() -> adapter.requireActiveSession(null))
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.UNAUTHENTICATED));
        assertThatThrownBy(() -> adapter.requireActiveSession(new MemberSession(member, null)))
                .isInstanceOf(NotificationException.class);
        verifyNoInteractions(repository);
    }

    @Test void memberActiveStateDelegatesToAuthRepository() {
        when(repository.countActiveMember(member)).thenReturn(1L);
        assertThat(adapter.isActiveMember(member)).isTrue();
        when(repository.countActiveMember(member)).thenReturn(0L);
        assertThat(adapter.isActiveMember(member)).isFalse();
        assertThat(adapter.isActiveMember(null)).isFalse();
    }

    @Test void databaseFailuresFailClosedWithoutLeakingDetails() {
        when(repository.findUsableSession(session, member)).thenThrow(new IllegalStateException("secret jdbc details"));
        assertThatThrownBy(() -> adapter.requireActiveSession(new MemberSession(member, session)))
                .isInstanceOfSatisfying(NotificationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEPENDENCY_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("secret jdbc details");
                });
        when(repository.countActiveMember(member)).thenThrow(new IllegalStateException("secret jdbc details"));
        assertThatThrownBy(() -> adapter.isActiveMember(member))
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEPENDENCY_UNAVAILABLE));
    }
}

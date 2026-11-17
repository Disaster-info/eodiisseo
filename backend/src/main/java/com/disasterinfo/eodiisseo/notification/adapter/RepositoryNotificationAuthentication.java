package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.Authentication;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.MemberSession;
import com.disasterinfo.eodiisseo.notification.NotificationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** Legacy opt-in adapter. The auth owner's shared bridge takes precedence when enabled. */
@Component
@ConditionalOnProperty(name = "eodiisseo.notification.auth.enabled", havingValue = "true")
@ConditionalOnProperty(name = "AUTH_BRIDGES_ENABLED", havingValue = "false", matchIfMissing = true)
public final class RepositoryNotificationAuthentication implements Authentication {
    private final SessionAccessService sessions;

    public RepositoryNotificationAuthentication(SessionAccessService sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    @Override
    public void requireActiveSession(MemberSession session) {
        if (session == null || session.memberId() == null || session.serviceSessionId() == null) {
            throw new NotificationException(NotificationException.Reason.UNAUTHENTICATED);
        }
        try {
            sessions.requireActiveSession(session.memberId(), session.serviceSessionId());
        } catch (ApiProblem failure) {
            throw new NotificationException(failure.status() == 401
                    ? NotificationException.Reason.UNAUTHENTICATED
                    : NotificationException.Reason.DEPENDENCY_UNAVAILABLE);
        } catch (RuntimeException failure) {
            throw new NotificationException(NotificationException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public boolean isActiveMember(UUID memberId) {
        if (memberId == null) return false;
        try {
            return sessions.isActiveMember(memberId);
        } catch (RuntimeException failure) {
            throw new NotificationException(NotificationException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }
}

package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.Authentication;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.MemberSession;
import com.disasterinfo.eodiisseo.notification.NotificationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Notification-side adapter around the existing auth team's DB checks.
 * Disabled until the auth owner confirms the contract and database readiness.
 * Does not parse bearer headers or mint trusted sessions from request parameters.
 */
@Component
@ConditionalOnProperty(name = "eodiisseo.notification.auth.enabled", havingValue = "true")
public final class RepositoryNotificationAuthentication implements Authentication {
    private final AuthRepository auth;

    public RepositoryNotificationAuthentication(AuthRepository auth) {
        this.auth = Objects.requireNonNull(auth);
    }

    @Override
    public void requireActiveSession(MemberSession session) {
        if (session == null || session.memberId() == null || session.serviceSessionId() == null) {
            throw new NotificationException(NotificationException.Reason.UNAUTHENTICATED);
        }
        try {
            // AuthRepository applies member ownership, ACTIVE, expiration and revocation checks.
            if (auth.findUsableSession(session.serviceSessionId(), session.memberId()) == null) {
                throw new NotificationException(NotificationException.Reason.UNAUTHENTICATED);
            }
        } catch (NotificationException known) {
            throw known;
        } catch (RuntimeException databaseFailure) {
            // Do not expose SQL errors or silently allow requests during auth outages.
            throw new NotificationException(NotificationException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public boolean isActiveMember(UUID memberId) {
        if (memberId == null) return false;
        try {
            return auth.countActiveMember(memberId) == 1;
        } catch (RuntimeException databaseFailure) {
            // The dispatch worker records a safe internal failure instead of sending.
            throw new NotificationException(NotificationException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }
}

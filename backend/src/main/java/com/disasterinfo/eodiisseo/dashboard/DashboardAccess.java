package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;

import java.util.Optional;
import java.util.UUID;

final class DashboardAccess {
    private DashboardAccess() {
    }

    static void requireSession(Optional<DashboardSessionVerifier> verifier, UUID memberId, UUID sessionId) {
        if (memberId == null || sessionId == null) {
            throw new DashboardException(DashboardException.Reason.UNAUTHENTICATED,
                    "An authenticated member and service session are required.");
        }
        verifier.orElseThrow(() -> new DashboardException(DashboardException.Reason.DEPENDENCY_UNAVAILABLE,
                "Authentication adapter is not connected.")).requireActiveSession(memberId, sessionId);
    }
}

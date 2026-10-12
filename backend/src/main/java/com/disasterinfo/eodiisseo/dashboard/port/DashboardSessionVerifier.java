package com.disasterinfo.eodiisseo.dashboard.port;

import java.util.UUID;

/** Authentication boundary; it does not parse or accept unverified client identity claims. */
public interface DashboardSessionVerifier {

    /**
     * Called for every protected request. The real authentication adapter must verify ACTIVE
     * membership, session ownership, absolute expiry and revocation. Both IDs must originate
     * from a successfully verified service bearer token, never arbitrary query/header values.
     * Reject invalid authentication with DashboardException.Reason.UNAUTHENTICATED.
     */
    void requireActiveSession(UUID memberId, UUID serviceSessionId);
}

package com.disasterinfo.eodiisseo.ai.summary.port;

import java.util.UUID;

/** Existing auth must verify ACTIVE, session ownership, expiry and revocation on every request. */
public interface SummaryAuthPort {
    void requireActiveSession(UUID memberId, UUID verifiedServiceSessionId);
}

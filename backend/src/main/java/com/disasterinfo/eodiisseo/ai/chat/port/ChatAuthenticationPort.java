package com.disasterinfo.eodiisseo.ai.chat.port;

import java.util.UUID;

/**
 * Authentication domain must verify the session belongs to this ACTIVE member and is unexpired
 * and unrevoked. Future HTTP adapters derive both IDs from verified Bearer authentication,
 * never from caller-supplied member/session headers or request properties.
 */
public interface ChatAuthenticationPort {
    void requireActiveSession(UUID memberId, UUID serviceSessionId);

    /**
     * Revalidate at completion and run persistence while holding the member/session protection
     * used by withdrawal. The production adapter must serialize this action with withdrawal
     * deletion and join its transaction, so an in-flight AI result cannot recreate deleted data.
     */
    void runWithActiveSession(UUID memberId, UUID serviceSessionId, Runnable persistenceAction);
}

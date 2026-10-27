package com.disasterinfo.eodiisseo.auth.entity;

import java.time.Instant;
import java.util.UUID;

/** Persisted session state; JDBC mapping is handled by AuthRepository. */
public record AuthSession(UUID sessionId, UUID memberId, String refreshTokenHash,
                          Instant expiresAt, Instant revokedAt) {
    public boolean accepts(String tokenHash, Instant now) {
        return tokenHash.equals(refreshTokenHash) && revokedAt == null && now.isBefore(expiresAt);
    }
}

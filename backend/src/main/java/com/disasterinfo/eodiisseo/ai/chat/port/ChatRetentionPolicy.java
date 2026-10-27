package com.disasterinfo.eodiisseo.ai.chat.port;

import java.time.Instant;

/**
 * Policy decision pending: FS says result generation + 90 days, while ERD says requested_at's
 * created_at + 90 days. No production default chooses between request and completion time.
 */
public interface ChatRetentionPolicy {

    boolean isApproved();

    Instant approvedExpiresAt(Instant requestedAt, Instant completedAt);
}

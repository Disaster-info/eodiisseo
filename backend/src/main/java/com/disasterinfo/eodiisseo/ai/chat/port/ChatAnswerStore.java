package com.disasterinfo.eodiisseo.ai.chat.port;

import com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Own persistence contract; fake implementations belong in tests only. */
public interface ChatAnswerStore {

    default boolean isAvailable() {
        return true;
    }

    /** Save answer and only its actually used official_resource IDs in one transaction. */
    void save(ChatAnswer answer, List<UUID> usedResourceIds);

    /** Delete at the previously approved expiry, including answer-resource links. */
    int deleteExpired(Instant now);

    /** Auth withdrawal flow must call immediately, even during the withdrawal recovery period. */
    int deleteByMemberId(UUID memberId);
}

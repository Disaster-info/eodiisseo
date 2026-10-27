package com.disasterinfo.eodiisseo.ai.chat.port;

import com.disasterinfo.eodiisseo.ai.chat.ChatText;

import java.util.Optional;

/**
 * NFR-AI-006 local minimization contract. Remove unnecessary personal data, precision location
 * and credentials before any external search/model call. This operation must not send the raw
 * question to an external service. No guessed regex/redaction policy is enabled by this domain.
 */
public interface ChatPromptPrivacyPort {

    Optional<PreparedQuestion> minimizeLocally(String rawQuestion);

    record PreparedQuestion(String safeQuestion, boolean approvedForOutbound) {
        public boolean usableForOutbound() {
            return approvedForOutbound && ChatText.hasText(safeQuestion);
        }

        @Override
        public String toString() {
            return "PreparedQuestion[safeQuestion=<redacted>, approvedForOutbound=" + approvedForOutbound + "]";
        }
    }
}

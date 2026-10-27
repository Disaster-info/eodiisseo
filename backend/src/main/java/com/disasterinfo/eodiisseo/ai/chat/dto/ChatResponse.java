package com.disasterinfo.eodiisseo.ai.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** API-CHAT-001 success keys. Source URLs remain internal until the wire contract is clarified. */
public record ChatResponse(AnswerType answerType, String answer, List<Source> sources, Instant generatedAt) {

    public ChatResponse {
        Objects.requireNonNull(answerType);
        Objects.requireNonNull(answer);
        sources = List.copyOf(sources);
        Objects.requireNonNull(generatedAt);
    }

    public enum AnswerType {
        AI, FALLBACK
    }

    public record Source(String organization, String title) {
        public Source {
            Objects.requireNonNull(organization);
            Objects.requireNonNull(title);
        }
    }

    @Override
    public String toString() {
        return "ChatResponse[answerType=" + answerType + ", answer=<redacted>, sources="
                + sources.size() + ", generatedAt=" + generatedAt + "]";
    }
}

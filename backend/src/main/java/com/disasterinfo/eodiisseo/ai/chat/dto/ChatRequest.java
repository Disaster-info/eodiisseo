package com.disasterinfo.eodiisseo.ai.chat.dto;

import com.disasterinfo.eodiisseo.ai.chat.ChatException;
import com.disasterinfo.eodiisseo.ai.chat.ChatText;

/** Internal input; the API document does not fix a JSON request property name yet. */
public record ChatRequest(String question) {
    public static final int MAX_LENGTH = 2000;

    public String validatedQuestion() {
        if (!ChatText.hasText(question)) {
            throw new ChatException(ChatException.Reason.INVALID_QUESTION,
                    "A non-empty question is required.");
        }
        // 저장 전에 거부한다 (외부 전송 한도와 같은 2,000자). 긴 원문이 fallback 답변과 함께 DB 에 쌓이는 것을 막음
        if (question.length() > MAX_LENGTH) {
            throw new ChatException(ChatException.Reason.INVALID_QUESTION,
                    "The question is too long.");
        }
        return question;
    }

    @Override
    public String toString() {
        return "ChatRequest[question=<redacted>]";
    }
}

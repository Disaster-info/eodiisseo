package com.disasterinfo.eodiisseo.ai.chat.dto;

import com.disasterinfo.eodiisseo.ai.chat.ChatException;
import com.disasterinfo.eodiisseo.ai.chat.ChatText;

/** Internal input; the API document does not fix a JSON request property name yet. */
public record ChatRequest(String question) {

    public String validatedQuestion() {
        if (!ChatText.hasText(question)) {
            throw new ChatException(ChatException.Reason.INVALID_QUESTION,
                    "A non-empty question is required.");
        }
        return question;
    }

    @Override
    public String toString() {
        return "ChatRequest[question=<redacted>]";
    }
}

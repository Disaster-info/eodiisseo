package com.disasterinfo.eodiisseo.ai.chat.controller;

import com.disasterinfo.eodiisseo.ai.chat.ChatException;
import com.disasterinfo.eodiisseo.ai.chat.ChatService;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatRequest;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Reuses the existing validation, privacy, evidence, fallback and transactional-save orchestration. */
@RestController
@ConditionalOnProperty(name = "eodiisseo.owned-http.enabled", havingValue = "true")
public class ChatController {
    private final ChatService guidance;

    public ChatController(ChatService guidance) {
        this.guidance = guidance;
    }

    @PostMapping("/api/v1/ai/guidance")
    public ChatResponse requestGuidance(@AuthenticationPrincipal MemberPrincipal principal,
                                        @RequestBody ChatRequest request) {
        if (principal == null || principal.memberId() == null || principal.sessionId() == null) {
            throw new ChatException(ChatException.Reason.UNAUTHENTICATED, "An active service session is required.");
        }
        return guidance.requestGuidance(principal.memberId(), principal.sessionId(), request);
    }
}

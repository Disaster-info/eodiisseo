package com.disasterinfo.eodiisseo.ai.chat.controller;

import com.disasterinfo.eodiisseo.ai.chat.ChatException;
import com.disasterinfo.eodiisseo.ai.chat.ChatService;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatRequest;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatResponse;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.service.OwnedRateLimiter;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<OwnedRateLimiter> limiter; // 운영 프로필에만 있는 빈. 슬라이스 테스트에선 없음

    public ChatController(ChatService guidance, ObjectProvider<OwnedRateLimiter> limiter) {
        this.guidance = guidance;
        this.limiter = limiter;
    }

    @PostMapping("/api/v1/ai/guidance")
    public ChatResponse requestGuidance(@AuthenticationPrincipal MemberPrincipal principal,
                                        @RequestBody ChatRequest request) {
        if (principal == null || principal.memberId() == null || principal.sessionId() == null) {
            throw new ChatException(ChatException.Reason.UNAUTHENTICATED, "An active service session is required.");
        }
        var limits = limiter.getIfAvailable();
        if (limits != null && !limits.allowAiGuidance(principal.memberId())) {
            throw new ChatException(ChatException.Reason.RATE_LIMITED, "Too many guidance requests.");
        }
        return guidance.requestGuidance(principal.memberId(), principal.sessionId(), request);
    }
}

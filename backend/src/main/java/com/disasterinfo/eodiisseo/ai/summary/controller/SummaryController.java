package com.disasterinfo.eodiisseo.ai.summary.controller;

import com.disasterinfo.eodiisseo.ai.summary.SummaryException;
import com.disasterinfo.eodiisseo.ai.summary.SummaryService;
import com.disasterinfo.eodiisseo.ai.summary.dto.SummaryResponse;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Enabled only with the owned-HTTP startup security guard; IDs come from verified authentication. */
@RestController
@ConditionalOnProperty(name = "eodiisseo.owned-http.enabled", havingValue = "true")
public class SummaryController {
    private final SummaryService summaries;

    public SummaryController(SummaryService summaries) {
        this.summaries = summaries;
    }

    @GetMapping("/api/v1/disasters/{disasterId}/ai-summary")
    public SummaryResponse getSummary(@AuthenticationPrincipal MemberPrincipal principal,
                                      @PathVariable("disasterId") String disasterId) {
        if (principal == null || principal.memberId() == null || principal.sessionId() == null) {
            throw new SummaryException(SummaryException.Reason.UNAUTHENTICATED);
        }
        UUID id;
        try {
            id = UUID.fromString(disasterId);
            if (!id.toString().equalsIgnoreCase(disasterId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new SummaryException(SummaryException.Reason.INVALID_DISASTER_ID);
        }
        return summaries.getSummary(principal.memberId(), principal.sessionId(), id);
    }
}

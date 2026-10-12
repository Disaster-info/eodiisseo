package com.disasterinfo.eodiisseo.ai.summary.dto;

import com.disasterinfo.eodiisseo.ai.summary.SummaryStatus;
import com.disasterinfo.eodiisseo.ai.summary.SummaryText;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact API-AISUM-001 5.2.1 success shape; official detail is provided by API-DASH-003. */
public record SummaryResponse(UUID disasterId, SummaryStatus status, String summary, Instant generatedAt) {
    public SummaryResponse {
        Objects.requireNonNull(disasterId);
        Objects.requireNonNull(status);
        if (status != SummaryStatus.COMPLETED) {
            summary = null;
            generatedAt = null;
        } else if (!SummaryText.hasContent(summary) || generatedAt == null) {
            throw new IllegalArgumentException("A completed summary requires text and generation time.");
        }
    }
}

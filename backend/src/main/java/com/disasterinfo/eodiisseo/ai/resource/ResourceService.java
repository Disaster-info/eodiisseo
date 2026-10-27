package com.disasterinfo.eodiisseo.ai.resource;

import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.port.OfficialResourceStore;
import com.disasterinfo.eodiisseo.ai.resource.port.ResourceApprovalPort;
import com.disasterinfo.eodiisseo.ai.resource.port.ResourceParagraphSearch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** CHAT-001 retrieval only. No automatic collection, approvals, dynamic-risk claims or fixture loading. */
@Service
public class ResourceService implements ChatOfficialResourceSearchPort {
    private static final Set<String> MOIS_HOSTS = Set.of("www.safety24.go.kr", "safety24.go.kr",
            "www.safekorea.go.kr", "safekorea.go.kr", "www.mois.go.kr", "mois.go.kr",
            "www.safetydata.go.kr", "safetydata.go.kr");
    private final OfficialResourceStore store;
    private final ResourceParagraphSearch paragraphs;
    private final Optional<ResourceApprovalPort> approvals;
    private final Clock clock;

    @Autowired
    public ResourceService(OfficialResourceStore store, ResourceParagraphSearch paragraphs,
                           Optional<ResourceApprovalPort> approvals) {
        this(store, paragraphs, approvals, Clock.systemUTC());
    }

    public ResourceService(OfficialResourceStore store, ResourceParagraphSearch paragraphs,
                           Optional<ResourceApprovalPort> approvals, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.paragraphs = Objects.requireNonNull(paragraphs);
        this.approvals = Objects.requireNonNull(approvals);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SearchResult search(String question) {
        try {
            var query = paragraphs.prepare(question);
            if (query.isEmpty() || approvals.isEmpty() || !store.isAvailable()) return empty();
            List<OfficialResourceDocument> candidates = store.searchCandidates(query.get().candidateTerms(), 60);
            if (candidates == null || candidates.size() > 60) return empty();
            List<OfficialResource> found = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            Set<String> approvedUrls = new HashSet<>();
            List<OfficialResourceDocument> approvedCandidates = new ArrayList<>();
            for (OfficialResourceDocument document : candidates) {
                if (document == null || !approved(document, clock.instant())) continue;
                // Publication/version ordering is not inferable from a label or checked_at.
                // Two currently approved IDs for one source require review-policy resolution.
                if (!seen.add(document.getResourceId()) || !approvedUrls.add(document.getSourceUrl())) return empty();
                approvedCandidates.add(document);
            }
            for (OfficialResourceDocument document : approvedCandidates) {
                List<String> selected = paragraphs.select(query.get(), document);
                if (selected.isEmpty()) continue;
                // Validate adapter output against actual complete original paragraphs before transmission.
                if (!validParagraphSelection(document.getContentText(), selected)) return empty();
                found.add(new OfficialResource(document.getResourceId(), document.getAgencyName(), document.getTitle(),
                        URI.create(document.getSourceUrl()), String.join("\n\n", selected), true, true, true));
                if (found.size() == 3) break;
            }
            if (found.isEmpty()) return empty();
            // A subset of a situational guide is not chosen as a standalone fallback instruction.
            return new SearchResult(found, true);
        } catch (RuntimeException failure) {
            // Repository/provider failures may contain sensitive credentials or document text.
            return empty();
        }
    }

    private boolean approved(OfficialResourceDocument document, Instant now) {
        if (!"행정안전부".equals(document.getAgencyName()) || document.getCheckedAt() == null
                || document.getCheckedAt().isAfter(now) || document.getContentText() == null || document.getContentText().isBlank()) return false;
        URI source = URI.create(document.getSourceUrl());
        if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null
                || !MOIS_HOSTS.contains(source.getHost().toLowerCase(java.util.Locale.ROOT))
                || source.getRawUserInfo() != null || source.getPort() != -1 || source.getFragment() != null) return false;
        var review = approvals.orElseThrow().findApproval(document.getResourceId());
        if (review == null || review.isEmpty()) return false;
        var value = review.get();
        return document.getResourceId().equals(value.resourceId()) && value.approvedPublicAiEvidence()
                && document.revisionFingerprint().equals(value.revisionFingerprint())
                && value.reviewedAt() != null && value.validUntil() != null
                && !value.reviewedAt().isAfter(now) && !value.reviewedAt().isBefore(document.getCheckedAt())
                && value.validUntil().isAfter(value.reviewedAt()) && now.isBefore(value.validUntil());
    }

    private static boolean validParagraphSelection(String original, List<String> selected) {
        String lineBreak = "(?>\\r\\n|[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029])";
        List<String> originals = List.of(original.split(lineBreak + "[\\p{Zs}\\t]*" + lineBreak + "(?:[\\p{Zs}\\t]*" + lineBreak + ")*"));
        int next = 0;
        int length = 0;
        if (selected.size() > 3) return false;
        for (String value : selected) {
            if (value == null || value.isBlank() || value.length() > 4_000) return false;
            while (next < originals.size() && !originals.get(next).equals(value)) next++;
            if (next == originals.size()) return false;
            next++;
            length += value.length();
        }
        return length <= 12_000;
    }

    private static SearchResult empty() { return new SearchResult(List.of(), false); }
}

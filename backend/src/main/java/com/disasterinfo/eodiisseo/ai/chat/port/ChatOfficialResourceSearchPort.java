package com.disasterinfo.eodiisseo.ai.chat.port;

import com.disasterinfo.eodiisseo.ai.chat.ChatText;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only ai.resource contract. Relevance and sufficiency are supplied by its official search. */
public interface ChatOfficialResourceSearchPort {

    /** The question has already passed local privacy minimization and outbound approval. */
    SearchResult search(String question);

    record SearchResult(List<OfficialResource> resources, boolean sufficientEvidence, BasicGuidance basicGuidance) {
        public SearchResult {
            resources = List.copyOf(Objects.requireNonNull(resources));
        }

        public SearchResult(List<OfficialResource> resources, boolean sufficientEvidence) {
            this(resources, sufficientEvidence, null);
        }
    }

    /**
     * The official resource module selects suitable basic guidance, rather than this domain choosing
     * the first search hit. The excerpt must be present verbatim in the identified official resource.
     */
    record BasicGuidance(UUID resourceId, String excerpt) {
        @Override
        public String toString() {
            return "BasicGuidance[resourceId=" + resourceId + ", excerpt=<redacted>]";
        }
    }

    /**
     * official_resource is owned by ai.resource; do not create its Entity in the chat domain.
     * approvedPublicAiEvidence certifies that the supplied content is approved public material
     * without unnecessary personal or precision-location data for external model transmission.
     */
    record OfficialResource(UUID resourceId, String organization, String title, URI sourceUrl,
                            String content, boolean official, boolean relevant, boolean approvedPublicAiEvidence) {
        public boolean usableAsEvidence() {
            return resourceId != null && official && relevant && approvedPublicAiEvidence
                    && ChatText.hasText(organization)
                    && ChatText.hasText(title)
                    && sourceUrl != null && sourceUrl.isAbsolute()
                    && ("https".equalsIgnoreCase(sourceUrl.getScheme())
                    || "http".equalsIgnoreCase(sourceUrl.getScheme()))
                    && sourceUrl.getHost() != null && sourceUrl.getUserInfo() == null
                    && ChatText.hasText(content);
        }

        @Override
        public String toString() {
            return "OfficialResource[resourceId=" + resourceId + ", content=<redacted>]";
        }
    }
}

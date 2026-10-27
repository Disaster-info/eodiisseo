package com.disasterinfo.eodiisseo.ai.resource.port;

import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfficialResourceStore {
    boolean isAvailable();
    Optional<OfficialResourceDocument> findById(UUID resourceId);
    /** Idempotent insert only. Existing IDs with different metadata/content must be rejected. */
    OfficialResourceDocument append(OfficialResourceDocument document);
    /** Bounded literal text search, not caller-provided SQL or wildcard expressions. */
    List<OfficialResourceDocument> searchCandidates(List<String> terms, int limit);
}

package com.disasterinfo.eodiisseo.ai.resource.port;

import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;

import java.util.List;
import java.util.Optional;

/** Retrieval seam: a future embedding/pgvector adapter can replace lexical ranking without changing chat. */
public interface ResourceParagraphSearch {
    Optional<Query> prepare(String question);
    List<String> select(Query query, OfficialResourceDocument document);

    record Query(String topic, List<String> candidateTerms, List<String> facetTerms, List<String> requiredTerms) {
        public Query { candidateTerms = List.copyOf(candidateTerms); facetTerms = List.copyOf(facetTerms); requiredTerms = List.copyOf(requiredTerms); }
        public Query(String topic, List<String> candidateTerms, List<String> facetTerms) { this(topic, candidateTerms, facetTerms, List.of()); }
    }
}

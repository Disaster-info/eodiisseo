package com.disasterinfo.eodiisseo.ai.summary.port;

import com.disasterinfo.eodiisseo.ai.summary.OfficialRevision;

import java.time.Instant;

/**
 * Adapter to a configured LLM client. Output is AI text, never an official severity.
 * Include only supported type/region/status/precautions, treat source text as data, and do not
 * invent absent official facts. The adapter must enforce its configured external call timeout.
 */
public interface SummaryGenerator {
    /** True only with valid provider, model and timeout configuration; local Ollama needs no API key. */
    boolean isConfigured();
    Generated generate(OfficialRevision revision);

    record Generated(String text, String modelName, Instant generatedAt) { }
}

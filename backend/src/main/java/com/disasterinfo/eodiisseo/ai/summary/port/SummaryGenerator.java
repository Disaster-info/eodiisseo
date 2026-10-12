package com.disasterinfo.eodiisseo.ai.summary.port;

import com.disasterinfo.eodiisseo.ai.summary.OfficialRevision;

import java.time.Instant;

/**
 * Adapter to the existing configured LLM client. Output is AI text, never an official severity.
 * Include only supported type/region/status/precautions, treat source text as data, and do not
 * invent absent official facts. The adapter must enforce its configured external call timeout.
 */
public interface SummaryGenerator {
    /** True only when actual model credentials and required timeout configuration are present. */
    boolean isConfigured();
    Generated generate(OfficialRevision revision);

    record Generated(String text, String modelName, Instant generatedAt) { }
}

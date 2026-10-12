package com.disasterinfo.eodiisseo.ai.summary.port;

import java.time.Instant;
import java.util.UUID;

/** Existing safe service_error_log contract; never supply exception text or official source text. */
public interface SummaryFailureRecorder {
    void record(Code code, UUID disasterId, UUID sourceRevisionId, Instant occurredAt);

    enum Code {
        REGISTRATION_FAILED, GENERATION_FAILED, INVALID_AI_RESULT, SOURCE_INCOMPLETE,
        DISPATCH_FAILED, STORAGE_FAILED
    }
}

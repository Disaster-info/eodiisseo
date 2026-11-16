package com.disasterinfo.eodiisseo.auth.exception;

import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import java.util.UUID;
import org.slf4j.MDC;

/** Reuse the request ID already returned in X-Trace-Id and attached to server logs. */
public final class ApiTraceId {
    private ApiTraceId() {}
    public static String current() {
        String trace=MDC.get(TraceIdFilter.MDC_KEY);
        return trace==null || trace.isBlank() ? UUID.randomUUID().toString() : trace;
    }
}

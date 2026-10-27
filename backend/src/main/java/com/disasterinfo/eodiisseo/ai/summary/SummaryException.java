package com.disasterinfo.eodiisseo.ai.summary;

/** The eventual shared HTTP error adapter supplies code/message/traceId; no local advice is added. */
public class SummaryException extends RuntimeException {
    private final Reason reason;

    public SummaryException(Reason reason) {
        super(switch (reason) {
            case UNAUTHENTICATED -> "An active authenticated session is required.";
            case NOT_FOUND -> "The disaster was not found.";
            case INVALID_DISASTER_ID -> "A disaster UUID is required.";
            case DEPENDENCY_UNAVAILABLE -> "The summary service is unavailable.";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNAUTHENTICATED, NOT_FOUND, INVALID_DISASTER_ID, DEPENDENCY_UNAVAILABLE
    }
}

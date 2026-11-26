package com.disasterinfo.eodiisseo.dashboard;

/** Internal failures for the authenticated API adapter to map to common errors. */
public class DashboardException extends RuntimeException {

    private final Reason reason;

    public DashboardException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_QUERY,
        UNAUTHENTICATED,
        FORBIDDEN,
        NOT_FOUND,
        DISASTER_COLLECTION_NOT_STARTED,
        DISASTER_COLLECTION_FAILED,
        DISASTER_COVERAGE_UNVERIFIED,
        DEPENDENCY_UNAVAILABLE,
        POLICY_UNRESOLVED
    }
}

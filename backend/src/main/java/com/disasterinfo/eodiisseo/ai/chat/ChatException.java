package com.disasterinfo.eodiisseo.ai.chat;

/** Safe internal failures for the future authenticated HTTP/global error adapter. */
public class ChatException extends RuntimeException {

    private final Reason reason;

    public ChatException(Reason reason, String safeMessage) {
        super(safeMessage);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_QUESTION(400),
        UNAUTHENTICATED(401),
        DEPENDENCY_UNAVAILABLE(500),
        POLICY_UNRESOLVED(500),
        PERSISTENCE_FAILURE(500);

        private final int httpStatus;

        Reason(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }
}

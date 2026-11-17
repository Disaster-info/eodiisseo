package com.disasterinfo.eodiisseo.notification;

/** Safe domain errors; the existing global HTTP adapter must add traceId. */
public final class NotificationException extends RuntimeException {
    public enum Reason {
        INVALID_REQUEST(400), UNAUTHENTICATED(401), FORBIDDEN(403), NOT_FOUND(404),
        DEVICE_OWNERSHIP_CONFLICT(409), DEVICE_TOKEN_CONFLICT(409), DEPENDENCY_UNAVAILABLE(500);
        private final int status;
        Reason(int status) { this.status = status; }
        public int status() { return status; }
    }
    private final Reason reason;
    public NotificationException(Reason reason) {
        super(switch (reason) {
            case INVALID_REQUEST -> "Invalid notification request.";
            case UNAUTHENTICATED -> "An active member and valid service session are required.";
            case FORBIDDEN -> "This notification device is not owned by the member.";
            case NOT_FOUND -> "Notification resource not found.";
            case DEVICE_OWNERSHIP_CONFLICT -> "The installation is registered to another member.";
            case DEVICE_TOKEN_CONFLICT -> "The push registration conflicts with an existing device.";
            case DEPENDENCY_UNAVAILABLE -> "Notification integration is unavailable.";
        });
        this.reason = reason;
    }
    public Reason reason() { return reason; }
    public String code() { return reason.name(); }
    public int status() { return reason.status(); }
}

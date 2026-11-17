package com.disasterinfo.eodiisseo.ai.ollama;

/** Provider details, prompts and connection settings are deliberately never attached as causes. */
public final class OllamaException extends RuntimeException {
    private final Reason reason;

    public OllamaException(Reason reason) {
        super("Local AI operation failed: " + reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public enum Reason {
        INVALID_BASE_URL, INVALID_MODEL, INVALID_TIMEOUT, CONFIGURATION_INVALID,
        INVALID_INPUT, CONTEXT_LIMIT_EXCEEDED, HTTP_BAD_REQUEST, HTTP_NOT_FOUND,
        MODEL_NOT_FOUND, HTTP_SERVER_ERROR, HTTP_UNAVAILABLE, HTTP_ERROR,
        CONNECTION_FAILED, REQUEST_TIMEOUT, INTERRUPTED, INVALID_RESPONSE,
        EMPTY_RESPONSE, RESPONSE_TOO_LARGE, UNSUPPORTED_OUTPUT
    }
}

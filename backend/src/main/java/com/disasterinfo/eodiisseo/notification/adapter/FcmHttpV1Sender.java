package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.FcmSender;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.Push;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.PushResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Real FCM HTTP v1 sender. Disabled by default; requires eodiisseo.fcm.enabled=true and a
 * valid service account. This adapter never retries POST, logs secrets, or marks a message sent
 * without a provider-issued message name. Caller owns deduplication and token-rotation fencing.
 */
@Component
@ConditionalOnProperty(name = "eodiisseo.fcm.enabled", havingValue = "true")
public final class FcmHttpV1Sender implements FcmSender {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_PROVIDER_BODY = 16_384;

    @FunctionalInterface
    interface Transport {
        Response post(URI url, String bearer, byte[] body) throws Exception;
    }
    record Response(int status, byte[] body) {}

    private final ServiceAccountFcmTokens tokens;
    private final Transport transport;

    @Autowired
    public FcmHttpV1Sender(ServiceAccountFcmTokens tokens) {
        this(tokens, defaultTransport());
    }

    FcmHttpV1Sender(ServiceAccountFcmTokens tokens, Transport transport) {
        this.tokens = Objects.requireNonNull(tokens);
        this.transport = Objects.requireNonNull(transport);
    }

    @Override public boolean isConfigured() { return tokens.isConfigured(); }

    @Override public PushResult send(String rawToken, Push push) {
        if (!isConfigured() || unsafeToken(rawToken, 4096) || push == null || push.disasterId() == null
                || unsafeText(push.title(), 4096) || unsafeText(push.content(), 8192)
                || unsafeText(push.disasterType(), 100) || unsafeText(push.regionName(), 500)) return failed();
        try {
            String bearer = tokens.accessToken();
            if (unsafeToken(bearer, 8192)) return failed();
            String projectId = tokens.projectId(); // derived only from validated service-account JSON
            URI endpoint = URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send");
            Map<String, Object> payload = Map.of("message", Map.of(
                    "token", rawToken,
                    "notification", Map.of("title", truncate(normalize(push.title()), 200),
                            "body", truncate(normalize(push.content()), 500)),
                    "data", Map.of("disasterId", push.disasterId().toString(),
                            "disasterType", normalize(push.disasterType()), "regionName", normalize(push.regionName()))));
            Response response = transport.post(endpoint, bearer, JSON.writeValueAsBytes(payload));
            if (response == null || response.body() == null || response.body().length > MAX_PROVIDER_BODY)
                return failed();
            JsonNode data = JSON.readTree(response.body());
            if (data == null || !data.isObject()) return failed();
            if (response.status() == 200) {
                JsonNode messageName = data.get("name");
                String prefix = "projects/" + projectId + "/messages/";
                if (messageName == null || !messageName.isString()) return failed();
                String name = messageName.asString();
                if (!name.startsWith(prefix) || name.length() <= prefix.length() || name.length() > 255
                        || name.contains(rawToken) || name.contains("\r") || name.contains("\n")) return failed();
                return new PushResult(true, false, name);
            }
            // A plain HTTP 404 is ambiguous. Only explicit FCM UNREGISTERED may deactivate a token.
            if (response.status() == 404 && explicitUnregistered(data))
                return new PushResult(false, true, null);
            return failed();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed();
        } catch (Exception ignored) {
            return failed();
        }
    }

    private static boolean explicitUnregistered(JsonNode root) {
        JsonNode error = root.get("error");
        if (error == null || !error.isObject()) return false;
        JsonNode details = error.get("details");
        if (details == null || !details.isArray()) return false;
        for (JsonNode detail : details) {
            if ("type.googleapis.com/google.firebase.fcm.v1.FcmError".equals(string(detail, "@type"))
                    && "UNREGISTERED".equals(string(detail, "errorCode"))) return true;
        }
        return false;
    }

    private static String string(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value != null && value.isString() ? value.asString() : null;
    }
    private static boolean unsafeToken(String value, int max) {
        return unsafeText(value, max) || value.contains("\r") || value.contains("\n");
    }
    private static boolean unsafeText(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }
    private static String normalize(String text) {
        return text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }
    private static String truncate(String value, int maxCodePoints) {
        return value.codePointCount(0, value.length()) <= maxCodePoints
                ? value : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
    private static PushResult failed() { return new PushResult(false, false, null); }

    private static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
        return (url, bearer, body) -> {
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.body());
        };
    }
}

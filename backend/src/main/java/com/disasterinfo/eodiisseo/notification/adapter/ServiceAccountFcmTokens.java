package com.disasterinfo.eodiisseo.notification.adapter;

import org.springframework.beans.factory.annotation.Value;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opt-in service-account OAuth exchange for FCM HTTP v1. No private material is logged or stored
 * in Git. Local/production credential path is supplied through FCM_SERVICE_ACCOUNT_PATH.
 *
 * Prefer Google's maintained OAuth library when the shared Gradle dependency policy allows it.
 * This small JDK-only adapter avoids changing another team's build configuration for now.
 */
@Component
@ConditionalOnProperty(name = "eodiisseo.fcm.enabled", havingValue = "true")
public final class ServiceAccountFcmTokens {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final URI GOOGLE_TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Pattern PROJECT_ID = Pattern.compile("[a-z][a-z0-9-]{3,62}");
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();
    private static final int MAX_OAUTH_BODY = 16_384;

    @FunctionalInterface
    interface TokenTransport {
        Response post(URI url, String formBody) throws Exception;
    }
    record Response(int status, byte[] body) {}
    private record Credentials(String projectId, String email, PrivateKey key) {}
    private record CachedToken(String value, Instant refreshAt) {}

    private final Credentials credentials;
    private final Clock clock;
    private final TokenTransport transport;
    private volatile CachedToken cached;

    @Autowired
    public ServiceAccountFcmTokens(@Value("${FCM_SERVICE_ACCOUNT_PATH:}") String credentialPath) {
        this(credentialPath, Clock.systemUTC(), defaultTransport());
    }

    ServiceAccountFcmTokens(String credentialPath, Clock clock, TokenTransport transport) {
        this.clock = Objects.requireNonNull(clock);
        this.transport = Objects.requireNonNull(transport);
        this.credentials = load(credentialPath);
    }

    boolean isConfigured() { return credentials != null; }
    String projectId() { return credentials == null ? null : credentials.projectId(); }

    /** Returns null on unavailable credentials/provider failures; never exposes OAuth error bodies. */
    synchronized String accessToken() {
        if (!isConfigured()) return null;
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.refreshAt())) return cached.value();
        try {
            String assertion = signedAssertion(credentials, now);
            String form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + assertion;
            Response response = transport.post(GOOGLE_TOKEN_URI, form);
            if (response == null || response.status() != 200 || response.body() == null
                    || response.body().length > MAX_OAUTH_BODY) return null;
            JsonNode data = JSON.readTree(response.body());
            if (data == null || !data.isObject()) return null;
            JsonNode tokenNode = data.get("access_token");
            JsonNode typeNode = data.get("token_type");
            JsonNode lifetimeNode = data.get("expires_in");
            if (tokenNode == null || !tokenNode.isString() || typeNode == null
                    || !"Bearer".equalsIgnoreCase(typeNode.asString()) || lifetimeNode == null
                    || !lifetimeNode.isNumber()) return null;
            String token = tokenNode.asString();
            long seconds = lifetimeNode.asLong();
            if (token.isBlank() || token.length() > 8192 || token.contains("\r") || token.contains("\n")
                    || seconds < 120 || seconds > 3600) return null;
            // Refresh early; no stale token is reused after a failed refresh.
            cached = new CachedToken(token, now.plusSeconds(seconds - Math.max(60, seconds / 10)));
            return token;
        } catch (Exception ignored) {
            cached = null;
            return null;
        }
    }

    private static Credentials load(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            Path file = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024) return null;
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
            if (root == null || !root.isObject() || !"service_account".equals(string(root, "type"))
                    || !GOOGLE_TOKEN_URI.toString().equals(string(root, "token_uri"))) return null;
            String project = string(root, "project_id");
            String email = string(root, "client_email");
            String pem = string(root, "private_key");
            if (project == null || !PROJECT_ID.matcher(project).matches() || email == null
                    || !email.endsWith(".gserviceaccount.com") || email.contains("\r")
                    || email.contains("\n") || pem == null) return null;
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] pkcs8 = Base64.getDecoder().decode(base64);
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            return new Credentials(project, email, key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String string(JsonNode json, String name) {
        JsonNode value = json.get(name);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static String signedAssertion(Credentials c, Instant now) throws Exception {
        byte[] header = JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT"));
        byte[] claims = JSON.writeValueAsBytes(Map.of(
                "iss", c.email(), "scope", SCOPE, "aud", GOOGLE_TOKEN_URI.toString(),
                "iat", now.getEpochSecond(), "exp", now.plusSeconds(3600).getEpochSecond()));
        String unsigned = URL64.encodeToString(header) + "." + URL64.encodeToString(claims);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(c.key());
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return unsigned + "." + URL64.encodeToString(signature.sign());
    }

    private static TokenTransport defaultTransport() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
        return (url, form) -> {
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.body());
        };
    }
}

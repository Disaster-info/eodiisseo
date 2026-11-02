package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.Push;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FcmAdapterTest {
    @TempDir Path temporary;
    static final JsonMapper JSON = JsonMapper.builder().build();

    private record Fixture(Path file, KeyPair pair) {}

    private Fixture serviceAccount() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded()) +
                "\n-----END PRIVATE KEY-----\n";
        Path path = temporary.resolve("test-only-service-account.json");
        Files.write(path, JSON.writeValueAsBytes(Map.of(
                "type", "service_account", "project_id", "example-project",
                "client_email", "robot@example-project.iam.gserviceaccount.com",
                "private_key", pem, "token_uri", "https://oauth2.googleapis.com/token")));
        return new Fixture(path, pair);
    }

    @Test void disabledWhenNoCredentialsAndNeverSends() {
        var source = new ServiceAccountFcmTokens("", Clock.systemUTC(), (uri, body) -> {
            throw new AssertionError("No token exchange permitted");
        });
        var sender = new FcmHttpV1Sender(source, (uri, bearer, body) -> {
            throw new AssertionError("No notification permitted");
        });
        assertThat(sender.isConfigured()).isFalse();
        assertThat(sender.send("device-token", push()).successful()).isFalse();
    }

    @Test void malformedOrMaliciousCredentialFileIsRejected() throws Exception {
        Path fake = temporary.resolve("invalid.json");
        Files.writeString(fake, "{\"type\":\"service_account\",\"project_id\":\"example-project\",\"token_uri\":\"https://evil.example/token\"}");
        assertThat(new ServiceAccountFcmTokens(fake.toString(), Clock.systemUTC(), (uri, body) -> {
            throw new AssertionError("Credentials must not trigger network calls");
        }).isConfigured()).isFalse();
    }

    @Test void oauthJwtIsSignedScopedAndCachedUntilRefresh() throws Exception {
        Fixture fixture = serviceAccount();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T02:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneId.of("UTC"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        AtomicInteger calls = new AtomicInteger();
        var tokens = new ServiceAccountFcmTokens(fixture.file().toString(), clock, (uri, body) -> {
            assertThat(uri).isEqualTo(URI.create("https://oauth2.googleapis.com/token"));
            assertThat(body).startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=");
            String jwt = body.substring(body.indexOf("&assertion=") + "&assertion=".length());
            String[] parts = jwt.split("\\.");
            assertThat(parts).hasSize(3);
            byte[] claims = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = JSON.readTree(claims);
            assertThat(payload.get("iss").asString()).isEqualTo("robot@example-project.iam.gserviceaccount.com");
            assertThat(payload.get("scope").asString()).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
            assertThat(payload.get("aud").asString()).isEqualTo("https://oauth2.googleapis.com/token");
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(fixture.pair().getPublic());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
            calls.incrementAndGet();
            return new ServiceAccountFcmTokens.Response(200,
                    "{\"access_token\":\"temporary-oauth\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8));
        });
        assertThat(tokens.isConfigured()).isTrue();
        assertThat(tokens.projectId()).isEqualTo("example-project");
        assertThat(tokens.accessToken()).isEqualTo("temporary-oauth");
        assertThat(tokens.accessToken()).isEqualTo("temporary-oauth");
        assertThat(calls.get()).isEqualTo(1);
        now.set(now.get().plusSeconds(3300));
        assertThat(tokens.accessToken()).isEqualTo("temporary-oauth");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void successfulSendUsesCorrectProjectAndMessagePayload() throws Exception {
        Fixture fixture = serviceAccount();
        var tokens = new ServiceAccountFcmTokens(fixture.file().toString(), Clock.systemUTC(), (uri, body) ->
                new ServiceAccountFcmTokens.Response(200,
                        "{\"access_token\":\"temporary-oauth\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8)));
        AtomicInteger calls = new AtomicInteger();
        var sender = new FcmHttpV1Sender(tokens, (uri, bearer, bytes) -> {
            calls.incrementAndGet();
            assertThat(uri.toString()).isEqualTo("https://fcm.googleapis.com/v1/projects/example-project/messages:send");
            assertThat(bearer).isEqualTo("temporary-oauth");
            JsonNode message = JSON.readTree(bytes).get("message");
            assertThat(message.get("token").asString()).isEqualTo("device-registration-token");
            assertThat(message.get("data").get("disasterId").asString()).isNotBlank();
            assertThat(message.get("notification").get("body").asString()).isEqualTo("공식 재난 안내 원문");
            return new FcmHttpV1Sender.Response(200,
                    "{\"name\":\"projects/example-project/messages/123\"}".getBytes(StandardCharsets.UTF_8));
        });
        assertThat(sender.send("device-registration-token", push()).successful()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void multilineOfficialContentIsSafeForNotification() throws Exception {
        Fixture fixture = serviceAccount();
        var tokens = new ServiceAccountFcmTokens(fixture.file().toString(), Clock.systemUTC(), (uri, body) ->
                new ServiceAccountFcmTokens.Response(200,
                        "{\"access_token\":\"temporary-oauth\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8)));
        var sender = new FcmHttpV1Sender(tokens, (uri, bearer, bytes) -> {
            JsonNode message = JSON.readTree(bytes).get("message");
            assertThat(message.get("notification").get("body").asString()).isEqualTo("호우 주의 하천 접근 금지");
            return new FcmHttpV1Sender.Response(200,
                    "{\"name\":\"projects/example-project/messages/123\"}".getBytes(StandardCharsets.UTF_8));
        });
        assertThat(sender.send("device-registration-token", new Push(UUID.randomUUID(),
                "FLOOD", "서울", "호우주의", "호우 주의\n하천 접근 금지")).successful()).isTrue();
    }

    @Test void onlyExplicitProviderUnregisteredMayDeactivateToken() throws Exception {
        Fixture fixture = serviceAccount();
        var tokens = new ServiceAccountFcmTokens(fixture.file().toString(), Clock.systemUTC(), (uri, body) ->
                new ServiceAccountFcmTokens.Response(200,
                        "{\"access_token\":\"temporary-oauth\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8)));
        String registered = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}";
        var unregistered = new FcmHttpV1Sender(tokens, (uri, bearer, bytes) ->
                new FcmHttpV1Sender.Response(404, registered.getBytes(StandardCharsets.UTF_8)));
        assertThat(unregistered.send("device-registration-token", push()).invalidToken()).isTrue();
        var notFound = new FcmHttpV1Sender(tokens, (uri, bearer, bytes) ->
                new FcmHttpV1Sender.Response(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}".getBytes(StandardCharsets.UTF_8)));
        assertThat(notFound.send("device-registration-token", push()).invalidToken()).isFalse();
        var quota = new FcmHttpV1Sender(tokens, (uri, bearer, bytes) ->
                new FcmHttpV1Sender.Response(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}".getBytes(StandardCharsets.UTF_8)));
        assertThat(quota.send("device-registration-token", push()).successful()).isFalse();
        assertThat(quota.send("device-registration-token", push()).invalidToken()).isFalse();
    }

    @Test void transportFailureDoesNotLeakSensitiveDetails() throws Exception {
        Fixture fixture = serviceAccount();
        var tokens = new ServiceAccountFcmTokens(fixture.file().toString(), Clock.systemUTC(), (uri, body) ->
                new ServiceAccountFcmTokens.Response(200,
                        "{\"access_token\":\"temporary-oauth\",\"token_type\":\"Bearer\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8)));
        var sender = new FcmHttpV1Sender(tokens, (uri, bearer, bytes) -> {
            throw new IllegalArgumentException("secret device-registration-token and temporary-oauth");
        });
        var result = sender.send("device-registration-token", push());
        assertThat(result.successful()).isFalse();
        assertThat(result.providerMessageId()).isNull();
        assertThat(result.toString()).doesNotContain("device-registration-token", "temporary-oauth");
    }

    private static Push push() {
        return new Push(UUID.randomUUID(), "EARTHQUAKE", "서울", "지진 관련 공식 안내", "공식 재난 안내 원문");
    }
}

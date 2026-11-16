package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.EodiisseoApplication;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.adapter.AesGcmNotificationTokenCipher;
import com.disasterinfo.eodiisseo.notification.adapter.RepositoryNotificationAuthentication;
import com.disasterinfo.eodiisseo.notification.persistence.JpaNotificationStore;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real canonical Flyway, JPA, auth bridge, HTTP filter and notification persistence together.
 * Only area ownership/selection and severity lookup are test fixtures: their production
 * domain adapters are not claimed to be complete. No FCM network call or real key is used.
 */
@EnabledIfEnvironmentVariable(named = "EODIISSEO_LOCAL_DB_TESTS", matches = "true")
class NotificationCanonicalHttpIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Severity TEST_SEVERITY = new Severity(UUID.randomUUID(), "HTTP_TEST_ONLY", 30000);
    @TempDir Path temporary;

    @Test
    @Timeout(180)
    void authenticatedNotificationHttpUsesCanonicalConstraintsAndEncryptedStorage() throws Exception {
        Assumptions.assumeTrue(new ClassPathResource(
                "db/migration/V2026102701__canonical_erd_4_2_1.sql").exists(),
                "Canonical ERD migration is required for this opt-in audit.");
        byte[] testKey = new byte[32];
        new SecureRandom().nextBytes(testKey);
        Path testKeyFile = temporary.resolve("test-only-notification-key.txt");
        try {
            Files.writeString(testKeyFile, Base64.getEncoder().encodeToString(testKey), StandardCharsets.US_ASCII);
        } finally {
            java.util.Arrays.fill(testKey, (byte) 0);
        }

        try (var database = new DisposableDatabase();
             var context = new SpringApplicationBuilder(EodiisseoApplication.class, FixtureConfiguration.class)
                     .web(WebApplicationType.SERVLET)
                     .run("--server.address=127.0.0.1", "--server.port=0",
                             "--spring.profiles.active=notification-canonical-http-audit",
                             "--spring.datasource.url=" + database.jdbcUrl(),
                             "--spring.datasource.username=eodiisseo_audit", "--spring.datasource.password=",
                             "--spring.config.import=optional:classpath:/nonexistent-notification-http-audit.properties",
                             "--spring.flyway.enabled=true", "--spring.flyway.locations=classpath:db/migration",
                             "--spring.jpa.hibernate.ddl-auto=validate",
                             "--AUTH_BRIDGES_ENABLED=true", "--eodiisseo.notification.auth.enabled=true",
                             "--eodiisseo.notification.persistence.enabled=true",
                             "--eodiisseo.notification.crypto.enabled=true",
                             "--EODIISSEO_NOTIFICATION_TOKEN_KEY_PATH=" + testKeyFile,
                             "--eodiisseo.owned-http.enabled=true", "--eodiisseo.fcm.enabled=false",
                             "--JWT_SECRET=notification-http-test-only-0123456789abcdef",
                             "--logging.level.root=WARN")) {
            context.getBean(Flyway.class).validate();
            assertThat(context.getBean(EntityManagerFactory.class).isOpen()).isTrue();
            assertThat(context.getBeansOfType(NotificationContracts.Authentication.class)).hasSize(1);
            assertThat(context.getBeansOfType(RepositoryNotificationAuthentication.class)).isEmpty();
            assertThat(context.getBean(Store.class)).isInstanceOf(JpaNotificationStore.class);
            assertThat(context.getBean(TokenCipher.class)).isInstanceOf(AesGcmNotificationTokenCipher.class);
            assertThat(context.getBeansOfType(FcmSender.class)).isEmpty();

            JdbcTemplate sql = database.sql();
            Actor first = createActor(sql);
            Actor second = createActor(sql);
            sql.update("INSERT INTO severity_level(severity_id,code,label,rank) VALUES (?,?,?,?)",
                    TEST_SEVERITY.id(), TEST_SEVERITY.code(), "test-only severity", TEST_SEVERITY.rank());
            UUID firstArea = UUID.randomUUID();
            UUID secondArea = UUID.randomUUID();
            TestOwnedAreas areas = context.getBean(TestOwnedAreas.class);
            areas.add(first.member(), firstArea);
            areas.add(second.member(), secondArea);
            context.getBean(Store.class).initializeSettings(first.member(), Instant.now());
            context.getBean(Store.class).initializeSettings(second.member(), Instant.now());
            Map<String, Object> untouchedSecond = sql.queryForMap(
                    "SELECT enabled,minimum_severity_id,allow_after_22,updated_at FROM notification_setting WHERE member_id=?",
                    second.member());

            TokenService tokens = context.getBean(TokenService.class);
            String firstJwt = tokens.issue(first.member(), first.session(), first.sessionExpiry());
            String secondJwt = tokens.issue(second.member(), second.session(), second.sessionExpiry());
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            String base = "http://127.0.0.1:" + port;
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
                assertError(send(client, base, "GET", "/api/v1/notification-settings", null, null),
                        401, "UNAUTHORIZED");
                var defaults = send(client, base, "GET", "/api/v1/notification-settings", firstJwt, null);
                assertThat(defaults.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(defaults.body()).get("enabled").asBoolean()).isFalse();

                var patched = send(client, base, "PATCH", "/api/v1/notification-settings", firstJwt,
                        Map.of("enabled", true, "interestAreaIds", List.of(firstArea.toString()),
                                "minimumSeverity", TEST_SEVERITY.code(), "nightNotificationEnabled", true));
                assertThat(patched.statusCode()).isEqualTo(200);
                JsonNode settings = JSON.readTree(patched.body());
                assertThat(settings.propertyNames()).containsExactlyInAnyOrder(
                        "enabled", "interestAreaIds", "minimumSeverity", "nightNotificationEnabled");
                assertThat(settings.get("enabled").asBoolean()).isTrue();
                assertThat(settings.get("interestAreaIds").get(0).asString()).isEqualTo(firstArea.toString());
                assertThat(settings.get("minimumSeverity").asString()).isEqualTo(TEST_SEVERITY.code());

                assertThat(send(client, base, "PATCH", "/api/v1/notification-settings", firstJwt,
                        Map.of("nightNotificationEnabled", false)).statusCode()).isEqualTo(200);
                Map<String, Object> storedFirst = sql.queryForMap(
                        "SELECT enabled,minimum_severity_id,allow_after_22 FROM notification_setting WHERE member_id=?",
                        first.member());
                assertThat(storedFirst).containsEntry("enabled", true)
                        .containsEntry("minimum_severity_id", TEST_SEVERITY.id()).containsEntry("allow_after_22", false);
                assertThat(sql.queryForMap(
                        "SELECT enabled,minimum_severity_id,allow_after_22,updated_at FROM notification_setting WHERE member_id=?",
                        second.member())).isEqualTo(untouchedSecond);

                assertError(send(client, base, "PATCH", "/api/v1/notification-settings", firstJwt,
                        Map.of("interestAreaIds", List.of(secondArea.toString()))), 404, "NOT_FOUND");
                assertError(send(client, base, "PATCH", "/api/v1/notification-settings", firstJwt,
                        Map.of("minimumSeverity", "UNKNOWN_TEST_ONLY")), 400, "INVALID_REQUEST");
                assertThat(sql.queryForMap(
                        "SELECT enabled,minimum_severity_id,allow_after_22 FROM notification_setting WHERE member_id=?",
                        first.member())).isEqualTo(storedFirst);
                assertThat(areas.findOwned(first.member())).containsExactly(new InterestArea(firstArea, true));

                String raw = "http-test-only-push-token";
                var registered = send(client, base, "PUT", "/api/v1/notification-devices/install-a", firstJwt,
                        Map.of("fcmToken", raw, "platform", "ANDROID"));
                assertThat(registered.statusCode()).isEqualTo(200);
                JsonNode deviceResponse = JSON.readTree(registered.body());
                assertThat(deviceResponse.propertyNames()).containsExactlyInAnyOrder("deviceId", "registered");
                assertThat(deviceResponse.get("deviceId").asString()).isEqualTo("install-a");
                assertThat(deviceResponse.get("registered").asBoolean()).isTrue();
                assertThat(registered.body().contains(raw)).isFalse();
                Map<String, Object> storedDevice = sql.queryForMap(
                        "SELECT device_token_id,member_id,token_hash,token_ciphertext FROM device_token WHERE device_id='install-a'");
                String ciphertext = (String) storedDevice.get("token_ciphertext");
                assertThat(ciphertext).startsWith("v1:").isNotEqualTo(raw);
                assertThat(storedDevice.get("token_hash")).isEqualTo(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))));
                assertThat(context.getBean(TokenCipher.class).decrypt(ciphertext).equals(raw)).isTrue();

                assertError(send(client, base, "PUT", "/api/v1/notification-devices/install-b", secondJwt,
                        Map.of("fcmToken", raw, "platform", "IOS")), 409, "DEVICE_TOKEN_CONFLICT");
                assertError(send(client, base, "PUT", "/api/v1/notification-devices/install-a", secondJwt,
                        Map.of("fcmToken", "another-test-only-token", "platform", "IOS")),
                        409, "DEVICE_OWNERSHIP_CONFLICT");
                assertError(send(client, base, "DELETE", "/api/v1/notification-devices/install-a", secondJwt, null),
                        403, "FORBIDDEN");
                assertThat(sql.queryForObject("SELECT count(*) FROM device_token", Long.class)).isEqualTo(1L);
                assertThat(sql.queryForMap(
                        "SELECT device_token_id,member_id,token_hash,token_ciphertext FROM device_token WHERE device_id='install-a'"))
                        .isEqualTo(storedDevice);

                assertThat(send(client, base, "PUT", "/api/v1/notification-devices/install-a", firstJwt,
                        Map.of("fcmToken", "rotated-http-test-only-token", "platform", "IOS")).statusCode()).isEqualTo(200);
                assertThat(sql.queryForObject("SELECT device_token_id FROM device_token WHERE device_id='install-a'", UUID.class))
                        .isEqualTo(storedDevice.get("device_token_id"));
                assertThat(sql.queryForObject("SELECT token_ciphertext FROM device_token WHERE device_id='install-a'", String.class))
                        .isNotEqualTo(ciphertext);
                assertThat(send(client, base, "DELETE", "/api/v1/notification-devices/install-a", firstJwt, null)
                        .statusCode()).isEqualTo(204);
                assertThat(sql.queryForObject("SELECT count(*) FROM device_token", Long.class)).isZero();

                assertThat(send(client, base, "POST", "/api/v1/auth/logout", firstJwt, null).statusCode()).isEqualTo(204);
                assertError(send(client, base, "GET", "/api/v1/notification-settings", firstJwt, null), 401, "UNAUTHORIZED");
                assertError(send(client, base, "PATCH", "/api/v1/notification-settings", firstJwt,
                        Map.of("enabled", false)), 401, "UNAUTHORIZED");
                assertError(send(client, base, "PUT", "/api/v1/notification-devices/install-a", firstJwt,
                        Map.of("fcmToken", raw, "platform", "ANDROID")), 401, "UNAUTHORIZED");
                assertError(send(client, base, "DELETE", "/api/v1/notification-devices/install-a", firstJwt, null),
                        401, "UNAUTHORIZED");
                assertThat(send(client, base, "GET", "/api/v1/notification-settings", secondJwt, null).statusCode())
                        .isEqualTo(200);
                assertThat(sql.queryForObject("SELECT count(*) FROM notification_event", Long.class)).isZero();
                assertThat(sql.queryForObject("SELECT count(*) FROM notification_delivery", Long.class)).isZero();
            }
        }
    }

    private static Actor createActor(JdbcTemplate sql) {
        UUID member = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiry = now.plus(Duration.ofDays(14));
        sql.update("INSERT INTO member(member_id,display_name,status,created_at) VALUES (?,'HTTP test member','ACTIVE',?)",
                member, Timestamp.from(now));
        sql.update("INSERT INTO member_role(member_id,role,granted_at) VALUES (?,'USER',?)", member, Timestamp.from(now));
        sql.update("INSERT INTO auth_session(session_id,member_id,refresh_token_hash,created_at,expires_at) VALUES (?,?,?,?,?)",
                session, member, UUID.randomUUID().toString().replace("-", "").repeat(2),
                Timestamp.from(now), Timestamp.from(expiry));
        return new Actor(member, session, expiry);
    }

    private record Actor(UUID member, UUID session, Instant sessionExpiry) {}

    private static HttpResponse<String> send(HttpClient client, String base, String method,
                                             String path, String jwt, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (jwt != null) request.header("Authorization", "Bearer " + jwt);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode error = JSON.readTree(response.body());
        assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message", "traceId");
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(error.get("traceId").asString()).isNotBlank()
                .isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean TestOwnedAreas testOwnedAreas() { return new TestOwnedAreas(); }
        @Bean SeverityReader testSeverityReader() {
            return new SeverityReader() {
                public Severity findByCode(String code) { return TEST_SEVERITY.code().equals(code) ? TEST_SEVERITY : null; }
                public Severity findById(UUID id) { return TEST_SEVERITY.id().equals(id) ? TEST_SEVERITY : null; }
            };
        }
    }

    /** Test data only: no SQL/query adapter for the area owner's production domain is invented. */
    static final class TestOwnedAreas implements InterestAreas {
        private final Map<UUID, List<InterestArea>> owned = new ConcurrentHashMap<>();
        void add(UUID member, UUID area) { owned.put(member, List.of(new InterestArea(area, false))); }
        public List<InterestArea> findOwned(UUID member) { return owned.getOrDefault(member, List.of()); }
        public void replaceSelection(UUID member, Set<UUID> selected) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("The notification settings transaction must be active.");
            }
            owned.compute(member, (id, current) -> {
                if (current == null || !current.stream().map(InterestArea::id).toList().containsAll(selected))
                    throw new NotificationException(NotificationException.Reason.NOT_FOUND);
                return current.stream().map(area -> new InterestArea(area.id(), selected.contains(area.id()))).toList();
            });
        }
        public boolean isRelated(UUID disaster, UUID area) {
            throw new UnsupportedOperationException("This HTTP audit never dispatches a disaster notification.");
        }
    }

    /** Creates and drops only its own generated database on the explicitly disposable audit cluster. */
    private static final class DisposableDatabase implements AutoCloseable {
        private final JdbcTemplate admin;
        private final String name = "notification_http_" + UUID.randomUUID().toString().replace("-", "");
        private final String url;
        private boolean created;

        private DisposableDatabase() {
            String base = System.getenv("EODIISSEO_AUDIT_JDBC_URL");
            Assumptions.assumeTrue("true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")),
                    "EODIISSEO_AUDIT_DISPOSABLE=true is required; production databases are never used.");
            Assumptions.assumeTrue(base != null, "EODIISSEO_AUDIT_JDBC_URL is required for the local audit.");
            if (!base.matches("jdbc:postgresql://127\\.0\\.0\\.1:55433/postgres")) {
                throw new IllegalArgumentException("Only the disposable loopback audit cluster on 55433 is allowed.");
            }
            admin = new JdbcTemplate(new DriverManagerDataSource(base, "eodiisseo_audit", ""));
            admin.execute("CREATE DATABASE " + name);
            created = true;
            url = base.substring(0, base.length() - "postgres".length()) + name;
        }

        private String jdbcUrl() { return url; }
        private JdbcTemplate sql() {
            return new JdbcTemplate(new DriverManagerDataSource(url, "eodiisseo_audit", ""));
        }

        @Override public void close() {
            if (created && name.matches("notification_http_[a-f0-9]{32}")) {
                admin.execute("DROP DATABASE " + name + " WITH (FORCE)");
            } else if (created) {
                throw new IllegalStateException("Refusing to drop an unexpected audit database.");
            }
        }
    }
}
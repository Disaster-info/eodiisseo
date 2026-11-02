package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.EodiisseoApplication;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryAuthPort;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import com.disasterinfo.eodiisseo.notification.NotificationContracts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresApplicationStartupTest {
    @Test
    void productionMigrationsMustSupportRealJpaAndWebStartupBeforeApiActivation() throws Exception {
        // A pre-created JPA fixture cannot validate the production migration path: Flyway rejects
        // it as a non-empty schema without history. Start empty and keep all production checks on.
        try (var database = new EmptyAuditSchema();
             var context = new SpringApplicationBuilder(EodiisseoApplication.class)
                     .web(WebApplicationType.SERVLET)
                     .run("--server.port=0", "--spring.profiles.active=postgres-audit",
                             "--spring.datasource.url=" + database.jdbcUrl(),
                             "--spring.datasource.username=eodiisseo_audit", "--spring.datasource.password=",
                             "--JWT_SECRET=0123456789abcdef0123456789abcdef",
                             "--server.address=127.0.0.1",
                             "--spring.config.import=optional:classpath:/nonexistent-startup-audit.properties",
                             "--spring.jpa.hibernate.ddl-auto=validate")) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBeansOfType(ChatAuthenticationPort.class)).isEmpty();
            assertThat(context.getBeansOfType(SummaryAuthPort.class)).isEmpty();
            assertThat(context.getBeansOfType(DashboardSessionVerifier.class)).isEmpty();
            assertThat(context.getBeansOfType(NotificationContracts.Authentication.class)).isEmpty();
            Set<String> paths = context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet()
                    .stream().flatMap(mapping -> mapping.getPatternValues().stream()).collect(Collectors.toSet());
            assertThat(paths).doesNotContain("/api/v1/dashboard", "/api/v1/disasters",
                    "/api/v1/disasters/{disasterId}", "/api/v1/disasters/{disasterId}/ai-summary",
                    "/api/v1/ai/guidance", "/api/v1/notification-settings",
                    "/api/v1/notification-devices/{deviceId}");
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/dashboard")).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                assertThat(response.statusCode()).isEqualTo(404);
            }
        }
    }

    /** Disposable test namespace only; tables are created exclusively by production Flyway. */
    private static final class EmptyAuditSchema implements AutoCloseable {
        private final String baseUrl;
        private final String schema = "startup_audit_" + UUID.randomUUID().toString().replace("-", "");

        private EmptyAuditSchema() throws SQLException {
            baseUrl = System.getenv("EODIISSEO_AUDIT_JDBC_URL");
            Assumptions.assumeTrue(baseUrl != null && "true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")),
                    "An explicitly disposable PostgreSQL audit cluster is required.");
            if (!baseUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
                throw new IllegalArgumentException("Only a disposable loopback PostgreSQL audit cluster is allowed.");
            }
            execute("CREATE SCHEMA " + schema);
        }

        private String jdbcUrl() {
            return baseUrl + "?currentSchema=" + schema;
        }

        private void execute(String sql) throws SQLException {
            try (var connection = DriverManager.getConnection(baseUrl, "eodiisseo_audit", "");
                 var statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        @Override
        public void close() throws SQLException {
            if (!schema.matches("startup_audit_[a-f0-9]{32}")) {
                throw new IllegalStateException("Invalid generated fixture schema.");
            }
            execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}

package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.EodiisseoApplication;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryAuthPort;
import com.disasterinfo.eodiisseo.auth.security.SessionAuthenticationFilter;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import com.disasterinfo.eodiisseo.notification.NotificationContracts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresApplicationStartupTest {
    @Test
    void productionMigrationsAndRealSecurityProtectAllNineActivatedApis() throws Exception {
        // Start empty: a pre-created JPA fixture cannot prove the production migration path.
        try (var database = new EmptyAuditSchema();
             var context = new SpringApplicationBuilder(EodiisseoApplication.class)
                     .web(WebApplicationType.SERVLET)
                     .run("--server.port=0", "--spring.profiles.active=postgres-audit",
                             "--spring.datasource.url=" + database.jdbcUrl(),
                             "--spring.datasource.username=eodiisseo_audit", "--spring.datasource.password=",
                             "--JWT_SECRET=0123456789abcdef0123456789abcdef",
                             "--AUTH_BRIDGES_ENABLED=true", "--eodiisseo.owned-http.enabled=true",
                             "--server.address=127.0.0.1",
                             "--spring.config.import=optional:classpath:/nonexistent-startup-audit.properties",
                             "--spring.jpa.hibernate.ddl-auto=validate")) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBeansOfType(ChatAuthenticationPort.class)).hasSize(1);
            assertThat(context.getBeansOfType(SummaryAuthPort.class)).hasSize(1);
            assertThat(context.getBeansOfType(DashboardSessionVerifier.class)).hasSize(1);
            assertThat(context.getBeansOfType(NotificationContracts.Authentication.class)).hasSize(1);
            Set<String> routes = context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet()
                    .stream().flatMap(mapping -> mapping.getPatternValues().stream().flatMap(path ->
                            mapping.getMethodsCondition().getMethods().stream().map(method -> method.name() + " " + path)))
                    .collect(Collectors.toSet());
            assertThat(routes).containsAll(Set.of("GET /api/v1/dashboard", "GET /api/v1/disasters",
                    "GET /api/v1/disasters/{disasterId}", "GET /api/v1/disasters/{disasterId}/ai-summary",
                    "POST /api/v1/ai/guidance", "GET /api/v1/notification-settings", "PATCH /api/v1/notification-settings",
                    "PUT /api/v1/notification-devices/{deviceId}", "DELETE /api/v1/notification-devices/{deviceId}"));
            var proxy = context.getBean(FilterChainProxy.class);
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                for (var route : ownedRequests()) {
                    var probe = new MockHttpServletRequest(route[0], route[1]);
                    probe.setServletPath(route[1]);
                    var chain = proxy.getFilterChains().stream().filter(candidate -> candidate.matches(probe))
                            .findFirst().orElseThrow();
                    assertThat(chain.getFilters()).anyMatch(SessionAuthenticationFilter.class::isInstance)
                            .anyMatch(AuthorizationFilter.class::isInstance);
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route[1]))
                            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                            .method(route[0], route[0].equals("GET") || route[0].equals("DELETE")
                                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"));
                    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as(route[0] + " " + route[1]).isEqualTo(401);
                    var body = JsonMapper.builder().build().readTree(response.body());
                    assertThat(body.get("code").asString()).isEqualTo("UNAUTHORIZED");
                    assertThat(body.get("traceId").asString()).isNotBlank()
                            .isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow());
                }
            }
        }
    }

    private static List<String[]> ownedRequests() {
        String disaster = "/api/v1/disasters/00000000-0000-0000-0000-000000000001";
        String device = "/api/v1/notification-devices/startup-audit-installation";
        return List.of(new String[]{"GET", "/api/v1/dashboard"}, new String[]{"GET", "/api/v1/disasters"},
                new String[]{"GET", disaster}, new String[]{"GET", disaster + "/ai-summary"},
                new String[]{"POST", "/api/v1/ai/guidance"}, new String[]{"GET", "/api/v1/notification-settings"},
                new String[]{"PATCH", "/api/v1/notification-settings"}, new String[]{"PUT", device}, new String[]{"DELETE", device});
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

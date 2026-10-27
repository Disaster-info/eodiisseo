package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.EodiisseoApplication;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryAuthPort;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import com.disasterinfo.eodiisseo.notification.NotificationContracts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresApplicationStartupTest {
    @Test
    void realJpaAndWebContextStartWithoutExternalAdaptersOrProtectedApiMappings() throws Exception {
        try (var database = new PostgresAuditSupport();
             var context = new SpringApplicationBuilder(EodiisseoApplication.class)
                     .web(WebApplicationType.SERVLET)
                     .run("--server.port=0", "--spring.profiles.active=postgres-audit",
                             "--spring.datasource.url=" + database.jdbcUrl(),
                             "--spring.datasource.username=eodiisseo_audit", "--spring.datasource.password=",
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
}

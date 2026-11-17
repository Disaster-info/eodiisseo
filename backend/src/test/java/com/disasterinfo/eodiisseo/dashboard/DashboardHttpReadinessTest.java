package com.disasterinfo.eodiisseo.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/** No protected endpoint may become reachable before the existing authentication is connected. */
@SpringBootTest
@ActiveProfiles("test")
class DashboardHttpReadinessTest {
    @Autowired
    private RequestMappingHandlerMapping mappings;

    @Test
    void allNineProtectedContractsRemainUnmappedWithoutRealAuthentication() {
        var paths = mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()).toList();

        assertThat(paths).doesNotContain(
                "/api/v1/dashboard", "/api/v1/disasters", "/api/v1/disasters/{disasterId}",
                "/api/v1/disasters/{disasterId}/ai-summary", "/api/v1/ai/guidance",
                "/api/v1/notification-settings", "/api/v1/notification-devices/{deviceId}");
    }
}

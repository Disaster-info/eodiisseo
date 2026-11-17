package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardLocationReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardWeatherReader;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardSpringContextTest {

    @Test
    void serviceStartsWithoutDomainAdaptersAndUnconnectedRequestsFailExplicitly() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(DashboardService.class)) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBeansOfType(DashboardLocationReader.class)).isEmpty();
            assertThat(context.getBeansOfType(DashboardDisasterReader.class)).isEmpty();
            assertThat(context.getBeansOfType(DashboardWeatherReader.class)).isEmpty();
            DashboardService service = context.getBean(DashboardService.class);

            assertThatThrownBy(() -> service.getDashboard(
                    UUID.fromString("11111111-1111-4111-8111-111111111111"),
                    new DashboardQuery(null, null, null)))
                    .isInstanceOfSatisfying(DashboardException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(DashboardException.Reason.DEPENDENCY_UNAVAILABLE));
        }
    }
}

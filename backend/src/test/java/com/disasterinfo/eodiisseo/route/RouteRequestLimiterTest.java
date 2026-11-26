package com.disasterinfo.eodiisseo.route;

import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.route.service.RouteRequestLimiter;
import java.time.*;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RouteRequestLimiterTest {
    @Test void placeBudgetIsThirtyAndSeparateFromRouteBudget() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var member = UUID.randomUUID();
        var clock = Clock.fixed(Instant.parse("2026-11-24T00:00:30Z"),ZoneOffset.UTC);
        var limiter = new RouteRequestLimiter(jdbc,clock,manager);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(member),eq("PLACE_READ"),any(Timestamp.class)))
                .thenReturn(30,31);
        assertThatCode(() -> limiter.checkPlaces(member)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkPlaces(member)).isInstanceOf(BusinessException.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(member),eq("ROUTE_READ"),any(Timestamp.class)))
                .thenReturn(10,11);
        assertThatCode(() -> limiter.check(member)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(member)).isInstanceOf(ApiProblem.class);
        verify(manager,times(4)).commit(any());
    }
}

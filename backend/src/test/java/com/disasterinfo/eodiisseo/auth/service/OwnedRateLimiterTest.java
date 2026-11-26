package com.disasterinfo.eodiisseo.auth.service;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.repository.RateLimitRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OwnedRateLimiterTest {
    @Test void rejectedAttemptStillCommitsItsCounter() {
        var db=mock(OwnedDatabase.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var member=UUID.randomUUID();var now=Instant.parse("2026-10-16T03:00:20Z");
        when(db.one(startsWith("insert into auth_request_window"),eq(member),eq(Instant.parse("2026-10-16T03:00:00Z")))).thenReturn(object("request_count",11));
        var limiter=new OwnedRateLimiter(new RateLimitRepository(db),Clock.fixed(now,ZoneOffset.UTC),manager);
        assertEquals(429,assertThrows(ApiProblem.class,()->limiter.checkGroupJoin(member)).status());
        verify(manager).commit(any());verify(manager,never()).rollback(any());
    }
    @Test void tenthAttemptIsStillAllowed() {
        var db=mock(OwnedDatabase.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var member=UUID.randomUUID();var now=Instant.parse("2026-10-16T03:00:00Z");
        when(db.one(startsWith("insert into auth_request_window"),eq(member),eq(now))).thenReturn(object("request_count",10));
        assertDoesNotThrow(()->new OwnedRateLimiter(new RateLimitRepository(db),Clock.fixed(now,ZoneOffset.UTC),manager).checkGroupJoin(member));
    }

    @Test void aiGuidanceDefaultAllowsTheTenthRequestAndRejectsTheEleventh() {
        var repository = mock(RateLimitRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        var member = UUID.randomUUID();
        var window = Instant.parse("2026-10-16T03:00:00Z");
        when(repository.incrementAiGuidanceWindow(member, window))
                .thenReturn(object("request_count", 10), object("request_count", 11));
        var limiter = new OwnedRateLimiter(repository, Clock.fixed(window.plusSeconds(59), ZoneOffset.UTC), manager);

        assertTrue(limiter.allowAiGuidance(member));
        assertFalse(limiter.allowAiGuidance(member));
        verify(manager, times(2)).commit(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -10})
    void nonpositiveAiGuidanceLimitKeepsTheExistingMinimum(int configuredLimit) {
        var repository = mock(RateLimitRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        var member = UUID.randomUUID();
        var window = Instant.parse("2026-10-16T03:00:00Z");
        when(repository.incrementAiGuidanceWindow(member, window))
                .thenReturn(object("request_count", 1), object("request_count", 2));
        var limiter = new OwnedRateLimiter(repository, Clock.fixed(window, ZoneOffset.UTC), manager);
        ReflectionTestUtils.setField(limiter, "aiGuidanceLimit", configuredLimit);

        assertTrue(limiter.allowAiGuidance(member));
        assertFalse(limiter.allowAiGuidance(member));
    }
}

package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationRetryWorkerTest {
    @Test void skipsPastAnOldestEventThatRemainsIneligibleAndRevisitsItAfterTheSweep() {
        OwnedDatabase db = mock(OwnedDatabase.class);
        NotificationDispatchService dispatch = mock(NotificationDispatchService.class);
        Instant now = Instant.parse("2026-11-24T01:00:00Z");
        Instant firstSentAt = now.minusSeconds(90);
        Instant secondSentAt = now.minusSeconds(60);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID firstEvent = UUID.randomUUID();
        UUID secondEvent = UUID.randomUUID();
        when(db.one(anyString(), eq(now), eq(now), eq(now)))
                .thenReturn(Map.of("event_id", firstEvent, "delivery_id", firstId, "sent_at", firstSentAt));
        when(db.one(contains("(d.sent_at,d.delivery_id)>(?,?)"), eq(now), eq(now), eq(now), eq(firstSentAt), eq(firstId)))
                .thenReturn(Map.of("event_id", secondEvent, "delivery_id", secondId, "sent_at", secondSentAt));
        when(db.one(contains("(d.sent_at,d.delivery_id)>(?,?)"), eq(now), eq(now), eq(now), eq(secondSentAt), eq(secondId)))
                .thenReturn(null);
        when(dispatch.retry(firstEvent, now))
                .thenReturn(new NotificationDispatchService.DispatchReport(0, 0, 0, 1, 0, true));
        var worker = new NotificationRetryWorker(db, dispatch, Clock.fixed(now, ZoneOffset.UTC), true);

        assertThat(worker.drainOne()).isTrue();
        assertThat(worker.drainOne()).isTrue();
        assertThat(worker.drainOne()).isFalse();
        assertThat(worker.drainOne()).isTrue();
        var order = inOrder(dispatch);
        order.verify(dispatch).retry(firstEvent, now);
        order.verify(dispatch).retry(secondEvent, now);
        order.verify(dispatch).retry(firstEvent, now);
        order.verifyNoMoreInteractions();
    }
}

package com.disasterinfo.eodiisseo.notification;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

/** Retry eligibility is persisted. PENDING/unknown submissions are deliberately never recovered as sendable. */
@Service @Profile("!test")
public class NotificationRetryWorker {
    private record Cursor(Instant sentAt, UUID deliveryId) {}

    private final OwnedDatabase db;private final NotificationDispatchService dispatch;private final Clock clock;private final boolean enabled;
    private Cursor cursor;
    public NotificationRetryWorker(OwnedDatabase db,NotificationDispatchService dispatch,Clock clock,
            @Value("${eodiisseo.notification.retry-worker.enabled:false}") boolean enabled){this.db=db;this.dispatch=dispatch;this.clock=clock;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${eodiisseo.runtime.poll-ms:1000}") public void tick(){if(enabled)drainOne();}
    public boolean drainOne() {
        Instant now=clock.instant();
        UUID event=nextEvent(now);
        if(event==null)return false;
        dispatch.retry(event,now);
        return true;
    }

    // One ineligible event must not keep the same oldest row at the head of every poll.
    private synchronized UUID nextEvent(Instant now) {
        String query="""
            select d.event_id,d.delivery_id,d.sent_at
            from notification_delivery d join notification_event e using(event_id)
            where d.result='FAILED' and d.error_code in ('FCM_RATE_LIMITED','FCM_TRANSIENT_FAILURE')
            and d.attempt_no<3 and d.sent_at is not null and e.member_id is not null and e.retention_until>?
            and e.disaster_id is not null and d.device_token_id is not null
            and d.sent_at+(case when d.attempt_no=1 then interval '30 seconds' else interval '120 seconds' end)<=?
            and (d.retry_not_before is null or d.retry_not_before<=?)
            and not exists(select 1 from notification_delivery n where n.event_id=d.event_id
                and n.target_ordinal=d.target_ordinal and n.attempt_no>d.attempt_no)
            """;
        String order=" order by d.sent_at,d.delivery_id limit 1";
        var row=cursor==null
                ? db.one(query+order,now,now,now)
                : db.one(query+" and (d.sent_at,d.delivery_id)>(?,?)"+order,now,now,now,cursor.sentAt(),cursor.deliveryId());
        if(row==null) {
            cursor=null;
            return null;
        }
        cursor=new Cursor(OwnedDatabase.instant(row.get("sent_at")),(UUID)row.get("delivery_id"));
        return (UUID)row.get("event_id");
    }
}

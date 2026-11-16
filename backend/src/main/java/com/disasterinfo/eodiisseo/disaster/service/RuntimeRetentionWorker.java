package com.disasterinfo.eodiisseo.disaster.service;
import com.disasterinfo.eodiisseo.notification.NotificationRetentionService;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
@Component @Profile("!test")
public class RuntimeRetentionWorker {
    private final NotificationRetentionService notifications;private final DisasterRetentionService disasters;private final Clock clock;private final boolean enabled;
    public RuntimeRetentionWorker(NotificationRetentionService notifications,DisasterRetentionService disasters,Clock clock,
            @Value("${eodiisseo.retention.enabled:false}") boolean enabled){this.notifications=notifications;this.disasters=disasters;this.clock=clock;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${eodiisseo.retention.poll-ms:3600000}",initialDelayString="${eodiisseo.retention.poll-ms:3600000}")
    public void tick(){if(enabled){notifications.purgeExpired(clock.instant());disasters.purgeOne(clock.instant());}}
}

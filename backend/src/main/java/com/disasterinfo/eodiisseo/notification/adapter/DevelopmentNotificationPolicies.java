package com.disasterinfo.eodiisseo.notification.adapter;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;

@Configuration(proxyBeanMethods=false) @Profile("!test")
public class DevelopmentNotificationPolicies {
    @Bean public NightPolicy configuredNightPolicy(@Value("${eodiisseo.notification.night-end:06:00}") String end) {
        LocalTime until=LocalTime.parse(end);
        if(!until.isBefore(LocalTime.of(22,0)))throw new IllegalArgumentException("Night end must not follow night start");
        return now->{LocalTime time=now.atZone(ZoneId.of("Asia/Seoul")).toLocalTime();
            return time.compareTo(LocalTime.of(22,0))>=0 || time.isBefore(until)?TimeWindow.NIGHT:TimeWindow.DAYTIME;};
    }
    @Bean public RetryPolicy configuredPushRetry() {
        return (event,delivery,now)-> {
            if(delivery.result()!=DeliveryResult.FAILED || delivery.sentAt()==null || delivery.attemptNo()>2 || delivery.attemptNo()<1)return false;
            if(!java.util.Set.of("FCM_RATE_LIMITED","FCM_TRANSIENT_FAILURE").contains(delivery.errorCode()))return false;
            return !now.isBefore(delivery.sentAt().plusSeconds(delivery.attemptNo()==1?30:120));
        };
    }
}

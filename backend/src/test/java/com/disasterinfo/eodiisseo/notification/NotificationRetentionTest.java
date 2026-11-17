package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class NotificationRetentionTest {
    @Test void signupInitializesConfirmedDefaultsWithoutOverwritingExistingSettings() {
        var f=new NotificationFixture();var lifecycle=new NotificationRetentionService(Optional.of(f.store));
        var previous=f.store.findSettings(f.member);lifecycle.onMemberCreated(f.member,f.now.plusSeconds(1));
        assertThat(f.store.findSettings(f.member)).isEqualTo(previous);
        f.store.settings.clear();lifecycle.onMemberCreated(f.member,f.now);
        assertThat(f.store.findSettings(f.member)).isEqualTo(new Settings(false,null,false,f.now));
    }
    @Test void withdrawalDropsTokensAndLinksButPreservesSettingsAndOriginalThirtyDayDeadline() {
        var f=new NotificationFixture();f.device("device-a");f.dispatcher().process(new DisasterChange(f.disasterId,ChangeKind.NEW,null),f.now);
        var event=f.store.events.values().iterator().next();var original=f.store.findSettings(f.member);
        var lifecycle=new NotificationRetentionService(Optional.of(f.store));lifecycle.onWithdrawal(f.member,f.now.plusSeconds(10));f.active=false;
        assertThat(f.store.devices).isEmpty();assertThat(f.store.findSettings(f.member)).isEqualTo(original);
        assertThat(f.store.events.values()).singleElement().satisfies(e -> {assertThat(e.memberId()).isNull();assertThat(e.retentionUntil()).isEqualTo(event.retentionUntil());assertThat(e.deidentifiedAt()).isNotNull();});
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> {assertThat(d.deviceTokenId()).isNull();assertThat(d.providerMessageId()).isNull();});
        assertThat(f.dispatcher().retry(event.id(),f.now.plusSeconds(20)).successfulDevices()).isZero();
    }
    @Test void expiryDeletesEventsAndDeliveriesExactlyAtDeadline() {
        var f=new NotificationFixture();f.device("device-a");f.dispatcher().process(new DisasterChange(f.disasterId,ChangeKind.NEW,null),f.now);
        var lifecycle=new NotificationRetentionService(Optional.of(f.store));
        assertThat(lifecycle.purgeExpired(f.now.plus(Duration.ofDays(30)).minusNanos(1))).isZero();
        assertThat(lifecycle.purgeExpired(f.now.plus(Duration.ofDays(30)))).isEqualTo(1);
        assertThat(f.store.events).isEmpty();assertThat(f.store.deliveries).isEmpty();
    }
    @Test void recoveryPeriodExpiryRemovesPreservedSettingsOnlyOnValidatedLifecycleCall() {
        var f=new NotificationFixture();new NotificationRetentionService(Optional.of(f.store)).onRecoveryPeriodExpired(f.member);
        assertThat(f.store.settings).isEmpty();
    }
    @Test void disasterDeletionClearsDeepLinkWithoutDeletingUnexpiredHistory() {
        var f=new NotificationFixture();f.dispatcher().process(new DisasterChange(f.disasterId,ChangeKind.NEW,null),f.now);
        var event=f.store.events.values().iterator().next();new NotificationRetentionService(Optional.of(f.store)).onDisasterDeleted(f.disasterId);
        assertThat(f.store.events.values()).singleElement().satisfies(e -> assertThat(e.disasterId()).isNull());
        assertThat(f.dispatcher().retry(event.id(),f.now.plusSeconds(1)).successfulDevices()).isZero();
    }
}

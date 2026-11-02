package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Safety regressions at the notification boundary. All Firebase and domain dependencies are
 * in-memory test doubles; these tests never make network requests or change production data.
 */
class NotificationSafetyRegressionTest {
    @Test
    void simultaneousDuplicateDisasterChangesCreateOnlyOneEventAndOnePush() throws Exception {
        var f = new NotificationFixture();
        f.device("device-a");
        var dispatcher = f.dispatcher();
        var change = new DisasterChange(f.disasterId, ChangeKind.NEW, null);
        List<Callable<NotificationDispatchService.DispatchReport>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            calls.add(() -> dispatcher.process(change, f.now));
        }

        try (var workers = Executors.newFixedThreadPool(8)) {
            var results = workers.invokeAll(calls);
            for (var result : results) {
                assertThat(result.get().internalFailures()).isZero();
            }
        }

        assertThat(f.store.events).hasSize(1);
        assertThat(f.store.deliveries).hasSize(1);
        assertThat(f.sends).isEqualTo(1);
    }

    @Test
    void oneDeviceFcmFailureCannotPreventAnotherDeviceFromReceivingTheAlert() {
        var f = new NotificationFixture();
        f.device("device-a");
        f.device("device-b");
        FcmSender sender = new FcmSender() {
            @Override public boolean isConfigured() { return true; }
            @Override public PushResult send(String rawToken, Push push) {
                f.sends++;
                if (rawToken.equals("test-token-device-a")) {
                    throw new IllegalStateException("credential should never be persisted: " + rawToken);
                }
                return new PushResult(true, false, "test-provider-message");
            }
        };
        var dispatcher = dispatcher(f, f.interests, sender);

        var report = dispatcher.process(new DisasterChange(f.disasterId, ChangeKind.NEW, null), f.now);

        assertThat(report.createdEvents()).isEqualTo(1);
        assertThat(report.failedDevices()).isEqualTo(1);
        assertThat(report.successfulDevices()).isEqualTo(1);
        assertThat(f.sends).isEqualTo(2);
        assertThat(f.store.deliveries.values())
                .extracting(Delivery::result)
                .containsExactly(DeliveryResult.FAILED, DeliveryResult.SUCCESS);
        assertThat(f.store.deliveries.values()).allSatisfy(delivery ->
                assertThat(delivery.toString()).doesNotContain("test-token-device-a"));
    }

    @Test
    void ambiguousProviderFailureDoesNotDeleteDeviceAndCanBeRetriedExplicitly() {
        var f = new NotificationFixture();
        f.device("device-a");
        FcmSender ambiguousFailure = new FcmSender() {
            @Override public boolean isConfigured() { return true; }
            @Override public PushResult send(String rawToken, Push push) {
                return new PushResult(false, false, null);
            }
        };
        var dispatcher = dispatcher(f, f.interests, ambiguousFailure);

        var report = dispatcher.process(new DisasterChange(f.disasterId, ChangeKind.NEW, null), f.now);

        assertThat(report.failedDevices()).isEqualTo(1);
        assertThat(report.successfulDevices()).isZero();
        assertThat(f.store.findActiveDevices(f.member)).hasSize(1);
        var event = f.store.events.values().iterator().next();
        assertThat(f.store.findDeliveries(event.id())).singleElement().satisfies(delivery -> {
            assertThat(delivery.result()).isEqualTo(DeliveryResult.FAILED);
            assertThat(delivery.errorCode()).isEqualTo("FCM_SEND_FAILED");
        });
        // The configured test retry policy is explicit; no background retry is invented.
        var retry = f.dispatcher().retry(event.id(), f.now.plusSeconds(1));
        assertThat(retry.successfulDevices()).isEqualTo(1);
        assertThat(f.store.findDeliveries(event.id()))
                .extracting(Delivery::attemptNo)
                .containsExactly((short) 1, (short) 2);
    }

    @Test
    void memberBecomingInactiveDuringFinalAreaCheckMustNotReceivePush() {
        var f = new NotificationFixture();
        f.device("device-a");
        InterestAreas areas = new InterestAreas() {
            private int checks;
            @Override public List<InterestArea> findOwned(UUID memberId) {
                return f.interests.findOwned(memberId);
            }
            @Override public void replaceSelection(UUID memberId, Set<UUID> selectedIds) {
                f.interests.replaceSelection(memberId, selectedIds);
            }
            @Override public boolean isRelated(UUID disasterId, UUID interestAreaId) {
                if (++checks == 3) f.active = false;
                return f.interests.isRelated(disasterId, interestAreaId);
            }
        };
        var dispatcher = dispatcher(f, areas, f.testFcm);

        var report = dispatcher.process(new DisasterChange(f.disasterId, ChangeKind.NEW, null), f.now);

        assertThat(report.successfulDevices()).isZero();
        assertThat(f.sends).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(delivery -> {
            assertThat(delivery.result()).isEqualTo(DeliveryResult.FAILED);
            assertThat(delivery.errorCode()).isEqualTo("MEMBER_INACTIVE");
        });
    }

    @Test
    void unresolvedPendingDeliveryMustNotBeRetriedOrResubmitted() {
        var f = new NotificationFixture();
        f.device("device-a");
        Device device = f.store.findActiveDevices(f.member).getFirst();
        Event event = new Event(UUID.randomUUID(), f.member, f.disasterId, "INITIAL",
                EventKind.INITIAL, f.warning.id(), f.disaster.title(),
                f.now, f.now.plusSeconds(86_400), null);
        assertThat(f.store.claimEvent(event)).isTrue();

        // Simulate a process that died after claiming the attempt, with the
        // external FCM outcome unknown. This is NOT proof that FCM received it.
        Delivery pending = new Delivery(UUID.randomUUID(), event.id(), device.id(),
                (short) 1, (short) 1, null, DeliveryResult.PENDING, null, null);
        assertThat(f.store.beginDelivery(pending)).isTrue();

        var dispatcher = f.dispatcher(); // Test retry policy would allow attempt 2 for FAILED.
        var retry = dispatcher.retry(event.id(), f.now.plusSeconds(3_600));
        assertThat(retry.connected()).isTrue();
        assertThat(retry.successfulDevices()).isZero();
        assertThat(retry.failedDevices()).isZero();
        assertThat(f.sends).isZero();

        // Reprocessing the same official event must not bypass event deduplication.
        var duplicate = dispatcher.process(
                new DisasterChange(f.disasterId, ChangeKind.NEW, null), f.now.plusSeconds(3_600));
        assertThat(duplicate.createdEvents()).isZero();
        assertThat(f.sends).isZero();
        assertThat(f.store.findDeliveries(event.id())).singleElement().satisfies(delivery -> {
            assertThat(delivery.result()).isEqualTo(DeliveryResult.PENDING);
            assertThat(delivery.attemptNo()).isEqualTo((short) 1);
        });
    }

    private static NotificationDispatchService dispatcher(NotificationFixture f, InterestAreas areas, FcmSender sender) {
        return new NotificationDispatchService(
                Optional.of(f.auth), Optional.of(f.store), Optional.of(areas),
                Optional.of(f.severityReader), Optional.of(id -> f.disasterId.equals(id) ? f.disaster : null),
                Optional.of(f.testCipher), Optional.of(sender),
                Optional.of((event, attempt, now) -> attempt.attemptNo() < 2),
                Optional.of(now -> TimeWindow.DAYTIME));
    }
}

package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Safe persisted outcome codes and explicit retry fencing, using only test infrastructure. */
class NotificationFcmOutcomeTest {
    @ParameterizedTest
    @EnumSource(value = PushOutcome.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    void failureClassificationIsPersistedAndOnlyUnregisteredInvalidatesTheRegistration(PushOutcome outcome) {
        var fixture = new NotificationFixture();
        fixture.device("device-a");
        var worker = worker(fixture, outcomeSender(fixture, outcome));

        var report = worker.process(initial(fixture), fixture.now);

        assertThat(report.successfulDevices()).isZero();
        assertThat(report.failedDevices()).isEqualTo(1);
        assertThat(fixture.store.findDevice("device-a").active()).isEqualTo(outcome != PushOutcome.UNREGISTERED);
        assertThat(fixture.store.deliveries.values()).singleElement().satisfies(attempt -> {
            assertThat(attempt.result()).isEqualTo(DeliveryResult.FAILED);
            assertThat(attempt.errorCode()).isEqualTo(outcome.errorCode());
            assertThat(attempt.providerMessageId()).isNull();
            assertThat(attempt.toString()).doesNotContain("test-token-device-a");
        });
    }

    enum Uncertain { LEGACY_RESULT, NULL_RESULT, SENDER_EXCEPTION, TIMEOUT, UNKNOWN }

    @ParameterizedTest
    @EnumSource(Uncertain.class)
    void uncertainAcceptanceCannotBeRetriedEvenByAPolicyThatAllowsEveryFailure(Uncertain uncertain) {
        var fixture = new NotificationFixture();
        fixture.device("device-a");
        FcmSender sender = new FcmSender() {
            @Override public boolean isConfigured() { return true; }
            @Override public PushResult send(String token, Push push) {
                fixture.sends++;
                return switch (uncertain) {
                    case LEGACY_RESULT -> new PushResult(false, false, null);
                    case NULL_RESULT -> null;
                    case SENDER_EXCEPTION -> throw new IllegalStateException("private " + token);
                    case TIMEOUT -> new PushResult(false, false, null, PushOutcome.NETWORK_TIMEOUT);
                    case UNKNOWN -> new PushResult(false, false, null, PushOutcome.ACCEPTANCE_UNKNOWN);
                };
            }
        };
        var worker = worker(fixture, sender);
        worker.process(initial(fixture), fixture.now);
        Event event = fixture.store.events.values().iterator().next();

        var retry = worker.retry(event.id(), fixture.now.plusSeconds(60));

        assertThat(retry.successfulDevices()).isZero();
        assertThat(retry.failedDevices()).isZero();
        assertThat(fixture.sends).isEqualTo(1);
        assertThat(fixture.store.findActiveDevices(fixture.member)).hasSize(1);
        assertThat(fixture.store.findDeliveries(event.id())).singleElement()
                .satisfies(attempt -> assertThat(attempt.attemptNo()).isEqualTo((short) 1));
    }

    @ParameterizedTest
    @EnumSource(value = PushOutcome.class, names = {"RATE_LIMITED", "TRANSIENT_FAILURE"})
    void definitiveTransientFailureStillRequiresAndHonorsTheExplicitRetryPolicy(PushOutcome outcome) {
        var fixture = new NotificationFixture();
        fixture.device("device-a");
        var worker = worker(fixture, outcomeSender(fixture, outcome));
        worker.process(initial(fixture), fixture.now);
        Event event = fixture.store.events.values().iterator().next();

        assertThat(worker.retry(event.id(), fixture.now.plusSeconds(60)).failedDevices()).isEqualTo(1);
        assertThat(fixture.sends).isEqualTo(2);
        assertThat(fixture.store.findDeliveries(event.id()))
                .extracting(Delivery::attemptNo).containsExactly((short) 1, (short) 2);
    }

    @Test
    void unclassifiedFailuresPersistedByOlderVersionsAreNotGuessedToBeRetryable() {
        var fixture = new NotificationFixture();
        fixture.device("device-a");
        var worker = worker(fixture, outcomeSender(fixture, PushOutcome.TRANSIENT_FAILURE));
        worker.process(initial(fixture), fixture.now);
        Event event = fixture.store.events.values().iterator().next();
        Delivery previous = fixture.store.findDeliveries(event.id()).getFirst();
        fixture.store.deliveries.put(previous.id(), new Delivery(previous.id(), previous.eventId(),
                previous.deviceTokenId(), previous.targetOrdinal(), previous.attemptNo(), previous.sentAt(),
                DeliveryResult.FAILED, null, "FCM_SEND_FAILED"));

        assertThat(worker.retry(event.id(), fixture.now.plusSeconds(60)).successfulDevices()).isZero();
        assertThat(fixture.sends).isEqualTo(1);
        assertThat(fixture.store.findDeliveries(event.id())).hasSize(1);
    }

    private static FcmSender outcomeSender(NotificationFixture fixture, PushOutcome outcome) {
        return new FcmSender() {
            @Override public boolean isConfigured() { return true; }
            @Override public PushResult send(String token, Push push) {
                fixture.sends++;
                return new PushResult(outcome == PushOutcome.ACCEPTED, outcome == PushOutcome.UNREGISTERED,
                        null, outcome);
            }
        };
    }

    private static DisasterChange initial(NotificationFixture fixture) {
        return new DisasterChange(fixture.disasterId, ChangeKind.NEW, null);
    }

    private static NotificationDispatchService worker(NotificationFixture fixture, FcmSender sender) {
        return new NotificationDispatchService(Optional.of(fixture.auth), Optional.of(fixture.store),
                Optional.of(fixture.interests), Optional.of(fixture.severityReader),
                Optional.of(id -> fixture.disaster), Optional.of(fixture.testCipher), Optional.of(sender),
                Optional.of((event, previous, now) -> true), Optional.of(now -> TimeWindow.DAYTIME));
    }
}
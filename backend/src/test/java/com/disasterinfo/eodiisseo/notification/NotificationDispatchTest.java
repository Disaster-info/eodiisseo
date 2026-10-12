package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class NotificationDispatchTest {
    private static DisasterChange initial(NotificationFixture f) { return new DisasterChange(f.disasterId,ChangeKind.NEW,null); }
    @Test void multipleMatchingAreasProduceOneMemberEventAndOnePushPerDevice() {
        var f=new NotificationFixture();f.device("device-a");f.device("device-b");
        var result=f.dispatcher().process(initial(f),f.now);
        assertThat(result.createdEvents()).isEqualTo(1);assertThat(result.successfulDevices()).isEqualTo(2);
        assertThat(f.store.events).hasSize(1);assertThat(f.store.deliveries).hasSize(2);assertThat(f.sends).isEqualTo(2);
    }
    @Test void duplicateInitialEventsDoNotResend() {
        var f=new NotificationFixture();f.device("device-a");f.dispatcher().process(initial(f),f.now);
        var result=f.dispatcher().process(initial(f),f.now.plusSeconds(1));
        assertThat(result.createdEvents()).isZero();assertThat(f.sends).isEqualTo(1);
    }
    @Test void officialSeverityIncreaseUsesNewKeyButRepeatedGradeDoesNotResend() {
        var f=new NotificationFixture();f.device("device-a");f.dispatcher().process(initial(f),f.now);
        f.disaster=new OfficialDisaster(f.disasterId,"HEAVY_RAIN","test region","test title","test content",f.severe,DisasterStatus.ONGOING,true);
        var change=new DisasterChange(f.disasterId,ChangeKind.CHANGED,f.warning);
        assertThat(f.dispatcher().process(change,f.now.plusSeconds(1)).createdEvents()).isEqualTo(1);
        assertThat(f.dispatcher().process(change,f.now.plusSeconds(2)).createdEvents()).isZero();
        assertThat(f.store.events.values()).extracting(Event::eventKey).containsExactly("INITIAL","SEVERITY:SEVERE");
    }
    @Test void unchangedOrFallingGradeDoesNotNotify() {
        var f=new NotificationFixture();f.device("device-a");
        assertThat(f.dispatcher().process(new DisasterChange(f.disasterId,ChangeKind.CHANGED,f.warning),f.now).createdEvents()).isZero();
        assertThat(f.dispatcher().process(new DisasterChange(f.disasterId,ChangeKind.CHANGED,f.severe),f.now).createdEvents()).isZero();
        assertThat(f.sends).isZero();
    }
    @Test void missingPreviousGradeDoesNotInventAnIncrease() {
        var f=new NotificationFixture();f.device("device-a");
        assertThat(f.dispatcher().process(new DisasterChange(f.disasterId,ChangeKind.CHANGED,null),f.now).createdEvents()).isZero();
    }
    @Test void unknownGradeCannotMeetAConfiguredMinimum() {
        var f=new NotificationFixture();f.device("device-a");
        f.disaster=new OfficialDisaster(f.disasterId,"HEAVY_RAIN","test region","test title","test content",null,DisasterStatus.ONGOING,true);
        assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();
    }
    @Test void noGradeRestrictionUsesNoInventedSeverity() {
        var f=new NotificationFixture();f.device("device-a");f.store.settings.put(f.member,new Settings(true,null,true,f.now));
        f.disaster=new OfficialDisaster(f.disasterId,"HEAVY_RAIN","test region","test title","test content",null,DisasterStatus.ONGOING,true);
        assertThat(f.dispatcher().process(initial(f),f.now).successfulDevices()).isEqualTo(1);
        assertThat(f.store.events.values()).allMatch(e -> e.severityId()==null);
    }
    @Test void belowMinimumAndOffMembersAreExcluded() {
        var f=new NotificationFixture();f.device("device-a");f.store.settings.put(f.member,new Settings(true,f.severe.id(),true,f.now));
        assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();
        f.store.settings.put(f.member,new Settings(false,null,true,f.now));
        assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();assertThat(f.sends).isZero();
    }
    @Test void inactiveMemberIsExcludedEvenWithStoredOnSettings() {
        var f=new NotificationFixture();f.device("device-a");f.active=false;
        assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();assertThat(f.sends).isZero();
    }
    @Test void seoul22HourBoundaryExcludesNightOptOut() {
        var f=new NotificationFixture();f.device("device-a");f.store.settings.put(f.member,new Settings(true,null,false,f.now));
        assertThat(f.dispatcher().process(initial(f),Instant.parse("2026-10-12T13:00:00Z")).createdEvents()).isZero();
        assertThat(f.dispatcher().process(initial(f),Instant.parse("2026-10-12T12:59:59Z")).successfulDevices()).isEqualTo(1);
    }
    @Test void processingThatCrosses22CannotSendUsingItsOldStartTime() {
        var f = new NotificationFixture(); f.device("device-a");
        f.store.settings.put(f.member,new Settings(true,null,false,f.now));
        var elapsed = new java.util.concurrent.atomic.AtomicLong();
        TokenCipher delayedCrypto = new TokenCipher() {
            @Override public String encrypt(String token) { return f.testCipher.encrypt(token); }
            @Override public String decrypt(String ciphertext) {
                elapsed.addAndGet(java.time.Duration.ofMillis(20).toNanos());
                return f.testCipher.decrypt(ciphertext);
            }
        };
        var worker = new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(delayedCrypto),Optional.of(f.testFcm),Optional.empty(),Optional.of(time -> TimeWindow.DAYTIME),elapsed::get);
        var result = worker.process(initial(f),Instant.parse("2026-10-12T12:59:59.999999999Z"));
        assertThat(f.sends).isZero();
        assertThat(result.successfulDevices()).isZero();
    }
    @Test void finalSlowAreaCheckCannotKeepThePreviousDaytimeEligibility() {
        var f = new NotificationFixture(); f.device("device-a");
        f.store.settings.put(f.member,new Settings(true,null,false,f.now));
        var elapsed = new java.util.concurrent.atomic.AtomicLong();
        InterestAreas delayedAreas = new InterestAreas() {
            int relatedChecks;
            @Override public List<InterestArea> findOwned(UUID memberId) { return f.interests.findOwned(memberId); }
            @Override public void replaceSelection(UUID memberId,java.util.Set<UUID> selected) { f.interests.replaceSelection(memberId,selected); }
            @Override public boolean isRelated(UUID disasterId,UUID areaId) {
                if (++relatedChecks == 3) elapsed.addAndGet(java.time.Duration.ofMillis(20).toNanos());
                return f.interests.isRelated(disasterId,areaId);
            }
        };
        var worker = new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(delayedAreas),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(f.testCipher),Optional.of(f.testFcm),Optional.empty(),Optional.of(time -> TimeWindow.DAYTIME),elapsed::get);
        worker.process(initial(f),Instant.parse("2026-10-12T12:59:59.990Z"));
        assertThat(f.sends).isZero();
    }
    @ParameterizedTest @ValueSource(booleans = {true,false})
    void registrationChangedDuringTheFinalAreaLookupCannotSendTheCapturedToken(boolean unregister) {
        var f = new NotificationFixture(); f.device("device-a");
        InterestAreas concurrentAreas = new InterestAreas() {
            int relatedChecks;
            @Override public List<InterestArea> findOwned(UUID memberId) { return f.interests.findOwned(memberId); }
            @Override public void replaceSelection(UUID memberId,java.util.Set<UUID> selected) { f.interests.replaceSelection(memberId,selected); }
            @Override public boolean isRelated(UUID disasterId,UUID areaId) {
                if (++relatedChecks == 3) {
                    if (unregister) f.service().unregisterDevice(f.session,"device-a");
                    else f.service().registerDevice(f.session,"device-a",new com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.DeviceRequest("test-current-token","IOS"));
                }
                return f.interests.isRelated(disasterId,areaId);
            }
        };
        var worker = new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(concurrentAreas),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(f.testCipher),Optional.of(f.testFcm),Optional.empty());
        assertThat(worker.process(initial(f),f.now).failedDevices()).isEqualTo(1);
        assertThat(f.sends).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> assertThat(d.errorCode()).isEqualTo("DEVICE_REGISTRATION_CHANGED"));
    }
    @Test void midnightDoesNotAutomaticallyEndNightWhenPolicyIsUnresolved() {
        var f=new NotificationFixture();f.device("device-a");f.store.settings.put(f.member,new Settings(true,null,false,f.now));
        var dispatcher=new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(f.testCipher),Optional.of(f.testFcm),Optional.empty(),Optional.of(time -> TimeWindow.UNRESOLVED));
        assertThat(dispatcher.process(initial(f),Instant.parse("2026-10-12T15:00:00Z")).createdEvents()).isZero();assertThat(f.sends).isZero();
    }
    @Test void missingNightPolicyHoldsOptOutRecipientsEvenWhenTheClockHourIsBelow22() {
        var f=new NotificationFixture();f.device("device-a");f.store.settings.put(f.member,new Settings(true,null,false,f.now));
        var dispatcher=new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(f.testCipher),Optional.of(f.testFcm),Optional.empty());
        assertThat(dispatcher.process(initial(f),f.now).createdEvents()).isZero();assertThat(f.sends).isZero();
    }
    @Test void nightOptInDoesNotRequireInventingANightEndTime() {
        var f=new NotificationFixture();f.device("device-a");
        var dispatcher=new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(f.testCipher),Optional.of(f.testFcm),Optional.empty());
        assertThat(dispatcher.process(initial(f),Instant.parse("2026-10-12T15:00:00Z")).successfulDevices()).isEqualTo(1);
    }
    @Test void deletedOrUnselectedAreasAreNotTargets() {
        var f=new NotificationFixture();f.device("device-a");f.owned=List.of(new InterestArea(f.area1,false));
        assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();
        f.owned=List.of();assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();
    }
    @Test void mockOrMissingOfficialDisasterNeverCreatesEvents() {
        var f=new NotificationFixture();f.device("device-a");
        f.disaster=new OfficialDisaster(f.disasterId,"HEAVY_RAIN","test region","test title","test content",f.warning,DisasterStatus.ONGOING,false);
        assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();
        f.disaster=null;assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();
    }
    @Test void unknownRegionDoesNotGenerateMadeUpPushContent() {
        var f=new NotificationFixture();f.device("device-a");
        f.disaster=new OfficialDisaster(f.disasterId,"HEAVY_RAIN",null,"test title","test content",f.warning,DisasterStatus.ONGOING,true);
        assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();assertThat(f.sends).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"TYPE","REGION","TITLE","CONTENT"})
    void unicodeWhitespaceOnlyOfficialFieldsCannotBeUsedAsKnownPushContent(String missingField) {
        var f = new NotificationFixture(); f.device("device-a");
        f.disaster = new OfficialDisaster(f.disasterId,missingField.equals("TYPE") ? "\u00a0" : "HEAVY_RAIN",
                missingField.equals("REGION") ? "\u00a0" : "test official region",
                missingField.equals("TITLE") ? "\u00a0" : "test official title",
                missingField.equals("CONTENT") ? "\u00a0" : "test official content",f.warning,DisasterStatus.ONGOING,true);
        assertThat(f.dispatcher().process(initial(f),f.now).createdEvents()).isZero();
        assertThat(f.sends).isZero();
    }
    @Test void noDeviceRecordsFailureAndNeverSuccess() {
        var f=new NotificationFixture();var result=f.dispatcher().process(initial(f),f.now);
        assertThat(result.failedDevices()).isEqualTo(1);assertThat(result.successfulDevices()).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> {assertThat(d.result()).isEqualTo(DeliveryResult.FAILED);assertThat(d.errorCode()).isEqualTo("NO_VALID_DEVICE");assertThat(d.sentAt()).isNull();});
    }
    @Test void unconfiguredFcmDoesNotExecuteAndRecordsSafeFailure() {
        var f=new NotificationFixture();f.device("device-a");f.fcmConfigured=false;
        assertThat(f.dispatcher().process(initial(f),f.now).failedDevices()).isEqualTo(1);assertThat(f.sends).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> assertThat(d.errorCode()).isEqualTo("FCM_UNAVAILABLE"));
    }
    @Test void withdrawalDuringDecryptionIsRecheckedBeforeFcm() {
        var f=new NotificationFixture();f.device("device-a");
        TokenCipher crypto=new TokenCipher() {
            @Override public String encrypt(String token) { return f.testCipher.encrypt(token); }
            @Override public String decrypt(String token) { f.active=false;return f.testCipher.decrypt(token); }
        };
        var dispatcher=new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(crypto),Optional.of(f.testFcm),Optional.empty());
        assertThat(dispatcher.process(initial(f),f.now).failedDevices()).isEqualTo(1);assertThat(f.sends).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> assertThat(d.errorCode()).isEqualTo("MEMBER_INACTIVE"));
    }
    @Test void unregisterDuringDecryptionIsRecheckedBeforeFcm() {
        var f=new NotificationFixture();f.device("device-a");
        TokenCipher crypto=new TokenCipher() {
            @Override public String encrypt(String token) { return f.testCipher.encrypt(token); }
            @Override public String decrypt(String token) { f.service().unregisterDevice(f.session,"device-a");return f.testCipher.decrypt(token); }
        };
        var dispatcher=new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(crypto),Optional.of(f.testFcm),Optional.empty());
        assertThat(dispatcher.process(initial(f),f.now).failedDevices()).isEqualTo(1);assertThat(f.sends).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> {assertThat(d.errorCode()).isEqualTo("DEVICE_REGISTRATION_CHANGED");assertThat(d.deviceTokenId()).isNull();});
    }
    @Test void missingRetryPolicyDoesNotInventRetryAttempts() {
        var f=new NotificationFixture();f.device("device-a");f.failFcm=true;f.dispatcher().process(initial(f),f.now);
        var dispatcher=new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(f.testCipher),Optional.of(f.testFcm),Optional.empty());
        var event=f.store.events.values().iterator().next();assertThat(dispatcher.retry(event.id(),f.now.plusSeconds(1)).connected()).isFalse();assertThat(f.sends).isEqualTo(1);
    }
    @Test void tokenRotationDuringDecryptionDoesNotSendTheOldToken() {
        var f=new NotificationFixture();f.device("device-a");
        TokenCipher crypto=new TokenCipher() {
            @Override public String encrypt(String token) { return f.testCipher.encrypt(token); }
            @Override public String decrypt(String token) {
                f.service().registerDevice(f.session,"device-a",new com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.DeviceRequest("test-rotated-token","IOS"));
                return f.testCipher.decrypt(token);
            }
        };
        var dispatcher=new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(crypto),Optional.of(f.testFcm),Optional.empty());
        assertThat(dispatcher.process(initial(f),f.now).failedDevices()).isEqualTo(1);assertThat(f.sends).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> assertThat(d.errorCode()).isEqualTo("DEVICE_REGISTRATION_CHANGED"));
    }
    @Test void fcmFailureDoesNotEscapeAndNeverStoresRawExceptionCredentials() {
        var f=new NotificationFixture();f.device("device-a");f.failFcm=true;
        var result=f.dispatcher().process(initial(f),f.now);
        assertThat(result.failedDevices()).isEqualTo(1);assertThat(result.internalFailures()).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> {assertThat(d.result()).isEqualTo(DeliveryResult.FAILED);assertThat(d.errorCode()).isEqualTo("FCM_SEND_FAILED");assertThat(d.toString()).doesNotContain("test-token-device-a");});
    }
    @Test void invalidTokenIsDeactivatedAndNotRetried() {
        var f=new NotificationFixture();f.device("device-a");f.invalidToken=true;f.dispatcher().process(initial(f),f.now);
        assertThat(f.store.findActiveDevices(f.member)).isEmpty();
        var event=f.store.events.values().iterator().next();assertThat(f.dispatcher().retry(event.id(),f.now.plusSeconds(1)).successfulDevices()).isZero();assertThat(f.sends).isEqualTo(1);
    }
    @Test void invalidResponseForOldTokenDoesNotDeactivateNewRegistration() {
        var f = new NotificationFixture(); f.device("device-a");
        FcmSender concurrentSender = new FcmSender() {
            @Override public boolean isConfigured() { return true; }
            @Override public PushResult send(String rawToken, Push push) {
                f.sends++;
                f.service().registerDevice(f.session,"device-a",new com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.DeviceRequest("test-new-valid-token","IOS"));
                return new PushResult(false,true,null);
            }
        };
        var worker = new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(f.testCipher),Optional.of(concurrentSender),Optional.empty());
        assertThat(worker.process(initial(f),f.now).failedDevices()).isEqualTo(1);
        assertThat(f.store.findDevice("device-a").active()).isTrue();
        assertThat(f.store.findActiveDevices(f.member)).hasSize(1);
        assertThat(f.sends).isEqualTo(1);
    }
    @Test void corruptedDecryptionNeverSendsATokenWhoseHashDoesNotMatchRegistration() {
        var f = new NotificationFixture(); f.device("device-a");
        TokenCipher wrongPlaintext = new TokenCipher() {
            @Override public String encrypt(String token) { return f.testCipher.encrypt(token); }
            @Override public String decrypt(String ciphertext) { return "test-another-devices-token"; }
        };
        var worker = new NotificationDispatchService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(id -> f.disaster),Optional.of(wrongPlaintext),Optional.of(f.testFcm),Optional.empty());
        assertThat(worker.process(initial(f),f.now).failedDevices()).isEqualTo(1);
        assertThat(f.sends).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> {
            assertThat(d.result()).isEqualTo(DeliveryResult.FAILED);
            assertThat(d.errorCode()).isEqualTo("INVALID_DEVICE_TOKEN");
            assertThat(d.sentAt()).isNull();
        });
    }
    @Test void unicodeWhitespaceOnlyDecryptedTokenIsNeverSentEvenWhenItsHashMatches() {
        var f = new NotificationFixture(); String blank = "\u00a0";
        f.store.devices.put("device-a",new Device(UUID.randomUUID(),f.member,"device-a",NotificationService.hash(blank),
                f.testCipher.encrypt(blank),Platform.IOS,true,f.now));
        assertThat(f.dispatcher().process(initial(f),f.now).failedDevices()).isEqualTo(1);
        assertThat(f.sends).isZero();
        assertThat(f.store.deliveries.values()).singleElement().satisfies(d -> assertThat(d.errorCode()).isEqualTo("INVALID_DEVICE_TOKEN"));
    }
    @Test void retriesFailedDeviceWithStableOrdinalAndIncrementedAttemptOnly() {
        var f=new NotificationFixture();f.device("device-a");f.failFcm=true;f.dispatcher().process(initial(f),f.now);
        f.failFcm=false;var event=f.store.events.values().iterator().next();
        assertThat(f.dispatcher().retry(event.id(),f.now.plusSeconds(1)).successfulDevices()).isEqualTo(1);
        assertThat(f.store.deliveries.values()).extracting(Delivery::attemptNo).containsExactly((short)1,(short)2);
        assertThat(f.store.deliveries.values()).allMatch(d -> d.targetOrdinal()==1);
        assertThat(f.dispatcher().retry(event.id(),f.now.plusSeconds(2)).successfulDevices()).isZero();assertThat(f.sends).isEqualTo(2);
    }
    @Test void settingsChangedToOffSuppressRetry() {
        var f=new NotificationFixture();f.device("device-a");f.failFcm=true;f.dispatcher().process(initial(f),f.now);
        var event=f.store.events.values().iterator().next();f.store.settings.put(f.member,new Settings(false,null,true,f.now));
        assertThat(f.dispatcher().retry(event.id(),f.now.plusSeconds(1)).successfulDevices()).isZero();assertThat(f.sends).isEqualTo(1);
    }
    @Test void unregisteredDeviceSuppressesRetryAndKeepsAnonymousHistory() {
        var f=new NotificationFixture();f.device("device-a");f.failFcm=true;f.dispatcher().process(initial(f),f.now);
        var event=f.store.events.values().iterator().next();f.service().unregisterDevice(f.session,"device-a");
        assertThat(f.dispatcher().retry(event.id(),f.now.plusSeconds(1)).successfulDevices()).isZero();
        assertThat(f.store.deliveries.values()).allMatch(d -> d.deviceTokenId()==null);
    }
    @Test void missingAdaptersLeaveWorkerDisconnectedWithoutThrowing() {
        var worker=new NotificationDispatchService(Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty());
        var f=new NotificationFixture();assertThat(worker.process(initial(f),f.now).connected()).isFalse();
    }
    @Test void testStoreAtomicEventClaimContractAllowsOnlyOneConcurrentWinner() throws Exception {
        var f=new NotificationFixture();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var calls=new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for(int i=0;i<16;i++) calls.add(() -> f.store.claimEvent(new Event(UUID.randomUUID(),f.member,f.disasterId,"INITIAL",EventKind.INITIAL,f.warning.id(),"test title",f.now,f.now.plusSeconds(2592000),null)));
            int winners=0;for(var future:pool.invokeAll(calls)) if(future.get()) winners++;
            assertThat(winners).isEqualTo(1);assertThat(f.store.events).hasSize(1);
        }
    }
}

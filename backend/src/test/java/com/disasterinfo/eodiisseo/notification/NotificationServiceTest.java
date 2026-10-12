package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    @Test void readsOnlyOwnSelectedPresentAreas() {
        var f = new NotificationFixture();
        assertThat(f.service().getSettings(f.session).interestAreaIds()).containsExactly(f.area1,f.area2);
        f.owned = List.of(new InterestArea(f.area2, true));
        assertThat(f.service().getSettings(f.session).interestAreaIds()).containsExactly(f.area2);
    }
    @Test void savesSelectionAndOfficialMinimumAndFlags() {
        var f = new NotificationFixture();
        var result = f.service().updateSettings(f.session,new SettingsRequest(false,List.of(f.area2.toString()),"SEVERE",false));
        assertThat(result).isEqualTo(new SettingsResponse(false,List.of(f.area2),"SEVERE",false));
    }
    @Test void nullableGradeIsUnrestrictedAndNoAreaIsAllowed() {
        var f = new NotificationFixture();
        var result = f.service().updateSettings(f.session,new SettingsRequest(true,List.of(),null,false));
        assertThat(result.minimumSeverity()).isNull(); assertThat(result.interestAreaIds()).isEmpty();
    }
    @Test void repeatedTargetIdsStillSelectOnlyOneOwnedArea() {
        var f=new NotificationFixture();
        var result=f.service().updateSettings(f.session,new SettingsRequest(true,List.of(f.area1.toString(),f.area1.toString()),null,false));
        assertThat(result.interestAreaIds()).containsExactly(f.area1);
    }
    @Test void foreignOrDeletedAreaRejectsWithoutChangingSettings() {
        var f = new NotificationFixture(); var previous=f.store.findSettings(f.member);
        assertThatThrownBy(() -> f.service().updateSettings(f.session,new SettingsRequest(false,List.of(UUID.randomUUID().toString()),"WARNING",false)))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(404));
        assertThat(f.store.findSettings(f.member)).isEqualTo(previous);
    }
    @ParameterizedTest @ValueSource(strings={"", "bad-id", "1-1-1-1-1"}) void rejectsMalformedAreaIds(String id) {
        var f=new NotificationFixture();
        assertThatThrownBy(() -> f.service().updateSettings(f.session,new SettingsRequest(true,List.of(id),null,false)))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(400));
    }
    @Test void rejectsUnknownGradeWithoutChangingSelection() {
        var f=new NotificationFixture();
        assertThatThrownBy(() -> f.service().updateSettings(f.session,new SettingsRequest(true,List.of(),"AI_INVENTED_SEVERITY",false))).isInstanceOf(NotificationException.class);
        assertThat(f.owned).allMatch(InterestArea::notifyEnabled);
    }
    @Test void missingSettingDoesNotReturnFabricatedDefault() {
        var f=new NotificationFixture();f.store.settings.clear();
        assertThatThrownBy(() -> f.service().getSettings(f.session)).isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(500));
    }
    @Test void registrationHashesEncryptsAndUpdatesLatestTokenWithSameId() {
        var f=new NotificationFixture();f.device("install-a");Device old=f.store.findDevice("install-a");
        f.service().registerDevice(f.session,"install-a",new DeviceRequest("test-replacement-token","IOS"));
        Device latest=f.store.findDevice("install-a");
        assertThat(latest.id()).isEqualTo(old.id());assertThat(latest.tokenHash()).hasSize(64).isNotEqualTo(old.tokenHash());
        assertThat(latest.tokenCiphertext()).isNotEqualTo("test-replacement-token");assertThat(latest.platform()).isEqualTo(Platform.IOS);
        assertThat(latest.toString()).doesNotContain("test-replacement-token",latest.tokenHash());
    }
    @Test void otherDeviceOwnerIsNeverOverwritten() {
        var f=new NotificationFixture();f.device("install-a");Device old=f.store.findDevice("install-a");
        f.store.devices.put("install-a",new Device(old.id(),UUID.randomUUID(),old.deviceId(),old.tokenHash(),old.tokenCiphertext(),old.platform(),true,old.updatedAt()));
        assertThatThrownBy(() -> f.service().registerDevice(f.session,"install-a",new DeviceRequest("replacement","IOS")))
                .isInstanceOfSatisfying(NotificationException.class,e -> {assertThat(e.status()).isEqualTo(409);assertThat(e.code()).isEqualTo("DEVICE_OWNERSHIP_CONFLICT");});
    }
    @Test void duplicateTokenAcrossDevicesIsRejected() {
        var f=new NotificationFixture();f.device("install-a");
        assertThatThrownBy(() -> f.service().registerDevice(f.session,"install-b",new DeviceRequest("test-token-install-a","ANDROID")))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(409));
    }
    @ParameterizedTest @ValueSource(strings={"", "WEB", "android"}) void rejectsInvalidPlatforms(String platform) {
        var f=new NotificationFixture();
        assertThatThrownBy(() -> f.service().registerDevice(f.session,"install-a",new DeviceRequest("token",platform)))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(400));
        assertThat(f.store.devices).isEmpty();
    }
    @Test void blankTokenIsNotStoredOrExposedInError() {
        var f=new NotificationFixture();
        assertThatThrownBy(() -> f.service().registerDevice(f.session,"install-a",new DeviceRequest(" ","IOS"))).isInstanceOf(NotificationException.class);
        assertThat(f.store.devices).isEmpty(); assertThat(new DeviceRequest("test-sensitive-token","IOS").toString()).doesNotContain("test-sensitive-token");
    }
    @Test void unicodeWhitespaceOnlyDeviceIdIsNotAValidInstallationIdentifier() {
        var f = new NotificationFixture();
        assertThatThrownBy(() -> f.service().registerDevice(f.session,"\u00a0",new DeviceRequest("test-sensitive-token","IOS")))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(400));
        assertThat(f.store.devices).isEmpty();
    }
    @Test void unicodeWhitespaceOnlyTokenIsRejectedWithoutPersistingCredentials() {
        var f = new NotificationFixture();
        assertThatThrownBy(() -> f.service().registerDevice(f.session,"device-a",new DeviceRequest("\u00a0","IOS")))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(400));
        assertThat(f.store.devices).isEmpty();
    }
    @Test void unicodeWhitespaceOnlyCiphertextFailsClosed() {
        var f = new NotificationFixture();
        TokenCipher invalidCipher = new TokenCipher() {
            @Override public String encrypt(String token) { return "\u00a0"; }
            @Override public String decrypt(String ciphertext) { return "test-sensitive-token"; }
        };
        var service = new NotificationService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(invalidCipher));
        assertThatThrownBy(() -> service.registerDevice(f.session,"device-a",new DeviceRequest("test-sensitive-token","IOS")))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(500));
        assertThat(f.store.devices).isEmpty();
    }
    @Test void nonblankOpaqueTokenIsHashedAndEncryptedWithoutTrimmingItsOriginalBytes() {
        var f = new NotificationFixture(); String token = " \u00a0test-push-token\u00a0 ";
        f.service().registerDevice(f.session,"device-a",new DeviceRequest(token,"IOS"));
        Device stored = f.store.findDevice("device-a");
        assertThat(stored.tokenHash()).isEqualTo(NotificationService.hash(token));
        assertThat(f.testCipher.decrypt(stored.tokenCiphertext())).isEqualTo(token);
    }
    @Test void missingCryptoFailsWithoutStoringToken() {
        var f=new NotificationFixture();var service=new NotificationService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.empty());
        assertThatThrownBy(() -> service.registerDevice(f.session,"install-a",new DeviceRequest("test-sensitive-token","IOS")))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.getMessage()).doesNotContain("test-sensitive-token"));
        assertThat(f.store.devices).isEmpty();
    }
    @Test void sessionRevokedWhileEncryptingCannotRegisterCredentials() {
        var f = new NotificationFixture();
        TokenCipher crypto = new TokenCipher() {
            @Override public String encrypt(String token) { f.revoked = true; return f.testCipher.encrypt(token); }
            @Override public String decrypt(String ciphertext) { return f.testCipher.decrypt(ciphertext); }
        };
        var service = new NotificationService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(crypto));
        assertThatThrownBy(() -> service.registerDevice(f.session,"device-a",new DeviceRequest("test-sensitive-token","IOS")))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(401));
        assertThat(f.store.devices).isEmpty();
    }
    @Test void sessionRevokedWhileLoadingAreasCannotReadSettings() {
        var f = new NotificationFixture();
        InterestAreas delayedAreas = new InterestAreas() {
            @Override public List<InterestArea> findOwned(UUID memberId) { f.revoked = true; return f.interests.findOwned(memberId); }
            @Override public void replaceSelection(UUID memberId,java.util.Set<UUID> selected) { f.interests.replaceSelection(memberId,selected); }
            @Override public boolean isRelated(UUID disasterId,UUID areaId) { return f.interests.isRelated(disasterId,areaId); }
        };
        var service = new NotificationService(Optional.of(f.auth),Optional.of(f.store),Optional.of(delayedAreas),Optional.of(f.severityReader),Optional.of(f.testCipher));
        assertThatThrownBy(() -> service.getSettings(f.session)).isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(401));
    }
    @Test void sessionRevokedWhileLookingUpSeverityCannotChangeSettingsOrSelection() {
        var f = new NotificationFixture(); var previous = f.store.findSettings(f.member);
        SeverityReader delayedSeverity = new SeverityReader() {
            @Override public Severity findByCode(String code) { f.revoked = true; return f.severityReader.findByCode(code); }
            @Override public Severity findById(UUID id) { return f.severityReader.findById(id); }
        };
        var service = new NotificationService(Optional.of(f.auth),Optional.of(f.store),Optional.of(f.interests),Optional.of(delayedSeverity),Optional.of(f.testCipher));
        assertThatThrownBy(() -> service.updateSettings(f.session,new SettingsRequest(false,List.of(),"WARNING",false)))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(401));
        assertThat(f.store.findSettings(f.member)).isEqualTo(previous);
        assertThat(f.owned).allMatch(InterestArea::notifyEnabled);
    }
    @Test void sessionRevokedWhileLookingUpDeviceCannotDeleteRegistration() {
        var f = new NotificationFixture(); f.device("device-a");
        Store delayedStore = mock(Store.class);
        when(delayedStore.findDevice("device-a")).thenAnswer(invocation -> { f.revoked = true; return f.store.findDevice("device-a"); });
        var service = new NotificationService(Optional.of(f.auth),Optional.of(delayedStore),Optional.of(f.interests),Optional.of(f.severityReader),Optional.of(f.testCipher));
        assertThatThrownBy(() -> service.unregisterDevice(f.session,"device-a"))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(401));
        verify(delayedStore,never()).deleteDevice(f.member,"device-a");
        assertThat(f.store.findActiveDevices(f.member)).hasSize(1);
    }
    @Test void ownerCanUnregisterAndReregistrationRequiresNewToken() {
        var f=new NotificationFixture();f.device("install-a");f.service().unregisterDevice(f.session,"install-a");
        assertThat(f.store.findActiveDevices(f.member)).isEmpty();
        assertThatThrownBy(() -> f.service().unregisterDevice(f.session,"install-a")).isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(404));
    }
    @Test void cannotUnregisterAnotherMembersDevice() {
        var f=new NotificationFixture();f.device("install-a");var d=f.store.findDevice("install-a");
        f.store.devices.put(d.deviceId(),new Device(d.id(),UUID.randomUUID(),d.deviceId(),d.tokenHash(),d.tokenCiphertext(),d.platform(),true,d.updatedAt()));
        assertThatThrownBy(() -> f.service().unregisterDevice(f.session,d.deviceId())).isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(403));
    }
    @Test void requiresSessionNotOnlyMemberId() {
        var f=new NotificationFixture();
        assertThatThrownBy(() -> f.service().getSettings(new MemberSession(f.member,null))).isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(401));
    }
    @Test void rejectsExpiredSession() {var f=new NotificationFixture();f.expired=true;assertThatThrownBy(() -> f.service().getSettings(f.session)).isInstanceOf(NotificationException.class);}
    @Test void rejectsRevokedSession() {var f=new NotificationFixture();f.revoked=true;assertThatThrownBy(() -> f.service().getSettings(f.session)).isInstanceOf(NotificationException.class);}
    @Test void rejectsWithdrawalPendingMember() {var f=new NotificationFixture();f.active=false;assertThatThrownBy(() -> f.service().getSettings(f.session)).isInstanceOf(NotificationException.class);}
    @Test void rejectsForeignSession() {var f=new NotificationFixture();assertThatThrownBy(() -> f.service().getSettings(new MemberSession(f.member,UUID.randomUUID()))).isInstanceOf(NotificationException.class);}
}

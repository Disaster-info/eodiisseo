package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.persistence.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Persistence orchestration unit tests. They do not establish PostgreSQL locking or schema correctness. */
class NotificationPersistenceContractTest {
    static final class DatabaseStub {
        final EntityManager em=mock(EntityManager.class);
        final UUID member=UUID.randomUUID(), event=UUID.randomUUID();
        boolean active=true, deidentifiedAfterLock;
        int mutations;
        Map<String,Object> finishedValues;
        final List<String> statements=new ArrayList<>();
        DatabaseStub() {
            when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
                String sql=invocation.getArgument(0);Query query=mock(Query.class);Map<String,Object> values=new HashMap<>();
                statements.add(sql);
                when(query.setParameter(anyString(),any())).thenAnswer(binding -> {values.put(binding.getArgument(0),binding.getArgument(1));return query;});
                when(query.getResultList()).thenAnswer(ignored -> {
                    if(sql.contains("FROM member WHERE")) return active || !sql.contains("status='ACTIVE'") ? List.of(member) : List.of();
                    if(sql.startsWith("SELECT member_id FROM notification_event"))
                        return deidentifiedAfterLock && sql.contains("FOR UPDATE") ? Collections.singletonList(null) : List.of(member);
                    return List.of();
                });
                when(query.executeUpdate()).thenAnswer(ignored -> {
                    mutations++;
                    if(sql.startsWith("UPDATE notification_delivery SET sent_at")) finishedValues=new HashMap<>(values);
                    return 1;
                });
                return query;
            });
        }
        JpaNotificationStore store() {return new JpaNotificationStore(em,mock(NotificationSettingRepository.class),mock(NotificationDeviceRepository.class),mock(NotificationEventRepository.class),mock(NotificationDeliveryRepository.class));}
    }
    @Test void deviceRegistrationCannotRecreateCredentialsAfterMemberFenceRejectsIt() {
        var db=new DatabaseStub();db.active=false;
        var device=new Device(UUID.randomUUID(),db.member,"test-install","h".repeat(64),"test-encrypted",Platform.IOS,true,Instant.now());
        assertThatThrownBy(() -> db.store().upsertDevice(device)).isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(401));
        assertThat(db.mutations).isZero();
    }
    @Test void settingsMutationCannotOverwritePreservedSettingsAfterWithdrawal() {
        var db=new DatabaseStub();db.active=false;
        assertThatThrownBy(() -> db.store().saveSettings(db.member,new Settings(false,null,false,Instant.now())))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(401));
        assertThat(db.mutations).isZero();
    }
    @Test void signupInitializationCannotInsertSettingsAfterMemberWithdrawal() {
        var db=new DatabaseStub();db.active=false;
        assertThatThrownBy(() -> db.store().initializeSettings(db.member,Instant.now()))
                .isInstanceOfSatisfying(NotificationException.class,e -> assertThat(e.status()).isEqualTo(401));
        assertThat(db.mutations).isZero();
    }
    @Test void deliveryClaimDoesNotAddIdentifiedHistoryAfterActiveMemberFenceRejectsIt() {
        var db=new DatabaseStub();db.active=false;
        assertThat(db.store().beginDelivery(new Delivery(UUID.randomUUID(),db.event,UUID.randomUUID(),(short)1,(short)1,null,DeliveryResult.PENDING,null,null))).isFalse();
        assertThat(db.mutations).isZero();
    }
    @Test void resultFinishingUsesLockedDeidentifiedOwnerAndCannotRestoreProviderIdentifier() {
        var db=new DatabaseStub();db.deidentifiedAfterLock=true;
        db.store().finishDelivery(new Delivery(UUID.randomUUID(),db.event,null,(short)1,(short)1,Instant.now(),DeliveryResult.SUCCESS,"test-sensitive-provider-id",null));
        assertThat(db.finishedValues).containsEntry("provider",null);
        assertThat(db.statements.getLast()).doesNotContain("device_token_id");
    }
    @Test void deliveryClaimRechecksDeidentifiedEventAfterAcquiringMemberFence() {
        var db=new DatabaseStub();db.deidentifiedAfterLock=true;
        assertThat(db.store().beginDelivery(new Delivery(UUID.randomUUID(),db.event,UUID.randomUUID(),(short)1,(short)1,null,DeliveryResult.PENDING,null,null))).isFalse();
        assertThat(db.mutations).isZero();
    }
    @Test void deliveryClaimRechecksDeviceBeforeCreatingHistory() {
        var db=new DatabaseStub();
        assertThat(db.store().beginDelivery(new Delivery(UUID.randomUUID(),db.event,UUID.randomUUID(),(short)1,(short)1,null,DeliveryResult.PENDING,null,null))).isFalse();
        assertThat(db.mutations).isZero();
    }
    @Test void activeResultFinishingRetainsActualProviderIdentifier() {
        var db=new DatabaseStub();
        db.store().finishDelivery(new Delivery(UUID.randomUUID(),db.event,UUID.randomUUID(),(short)1,(short)1,Instant.now(),DeliveryResult.SUCCESS,"test-provider-id",null));
        assertThat(db.finishedValues).containsEntry("provider","test-provider-id");
    }
    @Test void withdrawalAndFinishSerializeMemberThenEventBeforeIdentifiedDeliveryMutation() {
        var db=new DatabaseStub();db.store().withdrawMember(db.member,Instant.now());
        assertThat(db.statements.get(0)).contains("FROM member","FOR UPDATE");
        assertThat(db.statements.get(1)).contains("FROM notification_event","FOR UPDATE");
        assertThat(db.statements.get(2)).startsWith("UPDATE notification_delivery");
        assertThat(db.statements).noneMatch(sql -> sql.contains("SET retention_until"));
        db.statements.clear();db.store().finishDelivery(new Delivery(UUID.randomUUID(),db.event,null,(short)1,(short)1,Instant.now(),DeliveryResult.FAILED,null,"test-safe-error"));
        assertThat(db.statements.get(1)).contains("FROM member","FOR UPDATE");
        assertThat(db.statements.get(2)).contains("FROM notification_event","FOR UPDATE");
        assertThat(db.statements.get(3)).startsWith("UPDATE notification_delivery");
    }
    @Test void expiryWithNoLockedEventsDoesNotDeleteRowsCommittedAfterTheSnapshot() {
        var db = new DatabaseStub();
        assertThat(db.store().deleteExpiredEvents(Instant.now())).isZero();
        assertThat(db.mutations).isZero();
        assertThat(db.statements).singleElement().satisfies(sql -> assertThat(sql).contains("FOR UPDATE"));
    }
    @Test void expiryDeletesOnlyTheLockedEventSnapshotAndItsChildren() {
        var em = mock(EntityManager.class);
        UUID lockedEvent = UUID.randomUUID();
        List<Map<String,Object>> deletions = new ArrayList<>();
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Query query = mock(Query.class);
            Map<String,Object> values = new HashMap<>();
            when(query.setParameter(anyString(),any())).thenAnswer(binding -> {
                values.put(binding.getArgument(0),binding.getArgument(1)); return query;
            });
            when(query.getResultList()).thenReturn(List.of(lockedEvent));
            when(query.executeUpdate()).thenAnswer(ignored -> { deletions.add(new HashMap<>(values)); return 1; });
            return query;
        });
        var store = new JpaNotificationStore(em,mock(NotificationSettingRepository.class),mock(NotificationDeviceRepository.class),mock(NotificationEventRepository.class),mock(NotificationDeliveryRepository.class));
        assertThat(store.deleteExpiredEvents(Instant.now())).isEqualTo(1);
        assertThat(deletions).hasSize(2).allSatisfy(parameters -> {
            assertThat(parameters).doesNotContainKey("now");
            assertThat(parameters.get("ids")).isEqualTo(List.of(lockedEvent));
        });
    }
    @Test void databaseOutageDuringTokenUpdateIsNotMisreportedAsAClientConflict() {
        assertThatThrownBy(() -> updateWithDatabaseFailure(new jakarta.persistence.PersistenceException("test database connection unavailable")))
                .isInstanceOfSatisfying(NotificationException.class,failure -> {
                    assertThat(failure.status()).isEqualTo(500);
                    assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEPENDENCY_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("connection");
                });
    }
    @Test void actualSqlUniqueViolationDuringTokenUpdateStillReports409() {
        assertThatThrownBy(() -> updateWithDatabaseFailure(new jakarta.persistence.PersistenceException("test unique violation",new java.sql.SQLException("test conflicting hash","23505"))))
                .isInstanceOfSatisfying(NotificationException.class,failure -> {
                    assertThat(failure.status()).isEqualTo(409);
                    assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEVICE_TOKEN_CONFLICT);
                    assertThat(failure.getMessage()).doesNotContain("hash");
                });
    }
    private static void updateWithDatabaseFailure(RuntimeException databaseFailure) {
        EntityManager em = mock(EntityManager.class);
        UUID member = UUID.randomUUID();
        Device registered = new Device(UUID.randomUUID(),member,"device-a","h".repeat(64),"test-ciphertext",Platform.IOS,true,Instant.now());
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0); Query query = mock(Query.class);
            when(query.setParameter(anyString(),any())).thenReturn(query);
            when(query.getResultList()).thenReturn(List.of(member));
            if (sql.startsWith("UPDATE device_token")) when(query.executeUpdate()).thenThrow(databaseFailure);
            else when(query.executeUpdate()).thenReturn(0);
            return query;
        });
        var store = new JpaNotificationStore(em,mock(NotificationSettingRepository.class),mock(NotificationDeviceRepository.class),mock(NotificationEventRepository.class),mock(NotificationDeliveryRepository.class)) {
            @Override public Device findDevice(String id) { return registered; }
        };
        store.upsertDevice(registered);
    }
}

package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.dashboard.PostgresAuditSupport;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.persistence.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL/JPA transaction tests on an explicitly disposable ERD fixture, never deployed migrations. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationPostgresPersistenceTest {
    private PostgresAuditSupport db;
    private UUID member, disaster, severity;
    private final Instant now = Instant.parse("2026-10-12T03:00:00Z");

    @BeforeAll void openDisposableDatabase() throws Exception { db = new PostgresAuditSupport(); }
    @AfterAll void closeDisposableDatabase() throws Exception { if (db != null) db.close(); }

    @BeforeEach void seedOtherDomainForeignKeyFixtures() {
        db.sql("TRUNCATE member,disaster_type,severity_level CASCADE");
        member = UUID.randomUUID(); disaster = UUID.randomUUID(); severity = UUID.randomUUID();
        UUID type = UUID.randomUUID();
        db.sql("INSERT INTO member VALUES ('" + member + "','ACTIVE')");
        db.sql("INSERT INTO severity_level VALUES ('" + severity + "','WARNING','test warning',4)");
        db.sql("INSERT INTO disaster_type VALUES ('" + type + "','HEAVY_RAIN','test rain',true)");
        db.sql("INSERT INTO disaster VALUES ('" + disaster + "','" + type + "','test official title','2026-10-12T03:00:00Z','ONGOING','" + severity + "','OFFICIAL','2026-10-12T03:00:00Z',NULL)");
    }

    @Test void concurrentOfficialEventClaimsHaveOnePostgresqlWinner() throws Exception {
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) calls.add(() -> db.tx(em -> store(em).claimEvent(event("INITIAL",now))));
        assertThat(winners(calls)).isEqualTo(1);
        assertThat(db.count("notification_event")).isEqualTo(1);
    }

    @Test void concurrentDeviceActivationsCannotExceedMemberLimit() throws Exception {
        for (int i = 0; i < 9; i++) {
            register(device("active-" + i, Integer.toString(i)));
        }
        Device inactive = device("inactive", "a");
        register(inactive);
        db.tx(em -> {
            store(em).deactivateDevice(inactive.id(), inactive.tokenHash(), inactive.tokenCiphertext());
            return null;
        });
        assertThat(winners(List.of(
                () -> tryRegister(inactive),
                () -> tryRegister(device("new-b", "b")),
                () -> tryRegister(device("new-c", "c"))))).isEqualTo(1);
        assertThat(db.<List<Device>>tx(em -> store(em).findActiveDevices(member))).hasSize(10);
        assertThat(tryRegister(device("new-d", "d"))).isFalse();
        assertThat(tryRegister(device("active-0", "e"))).isTrue();
        assertThat(db.<List<Device>>tx(em -> store(em).findActiveDevices(member))).hasSize(10);
    }

    @Test void inactiveDeviceMustWaitForAnAvailableSlot() {
        Device inactive = device("inactive", "a");
        register(inactive);
        db.tx(em -> {
            store(em).deactivateDevice(inactive.id(), inactive.tokenHash(), inactive.tokenCiphertext());
            return null;
        });
        for (int i = 0; i < 10; i++) {
            register(device("active-" + i, Integer.toString(i)));
        }
        assertThat(tryRegister(inactive)).isFalse();
        db.tx(em -> { store(em).deleteDevice(member, "active-0"); return null; });
        assertThat(tryRegister(inactive)).isTrue();
        assertThat(db.<Device>tx(em -> store(em).findDevice(inactive.deviceId())).id()).isEqualTo(inactive.id());
    }

    @Test void concurrentRefreshesShareOneInstallationAndOtherMembersHaveIndependentCapacity() throws Exception {
        Device first = device("shared", "a");
        Device second = device("shared", "b");
        assertThat(winners(List.of(() -> tryRegister(first), () -> tryRegister(second)))).isEqualTo(2);
        assertThat(db.<List<Device>>tx(em -> store(em).findActiveDevices(member))).hasSize(1);
        for (int i = 0; i < 9; i++) {
            register(device("active-" + i, Integer.toString(i)));
        }
        UUID other = UUID.randomUUID();
        db.sql("INSERT INTO member VALUES ('" + other + "','ACTIVE')");
        register(new Device(UUID.randomUUID(), other, "independent", "c".repeat(64), "test-encrypted-c",
                Platform.IOS, true, now));
        assertThatThrownBy(() -> register(new Device(UUID.randomUUID(), other, "shared", "d".repeat(64),
                "test-encrypted-d", Platform.IOS, true, now)))
                .isInstanceOfSatisfying(NotificationException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEVICE_OWNERSHIP_CONFLICT));
    }

    @Test void withdrawalAndRegistrationSerializeOnTheSameMember() throws Exception {
        Device incoming = device("withdrawal-race", "a");
        assertThat(winners(List.of(
                () -> {
                    try {
                        register(incoming);
                    } catch (NotificationException failure) {
                        assertThat(failure.reason()).isEqualTo(NotificationException.Reason.UNAUTHENTICATED);
                    }
                    return true;
                },
                () -> db.tx(em -> {
                    em.createNativeQuery("UPDATE member SET status='WITHDRAWAL_PENDING' WHERE member_id=:id")
                            .setParameter("id", member).executeUpdate();
                    store(em).withdrawMember(member, now);
                    return true;
                })))).isEqualTo(2);
        assertThat(db.count("device_token")).isZero();
        assertThatThrownBy(() -> register(incoming)).isInstanceOfSatisfying(NotificationException.class,
                failure -> assertThat(failure.reason()).isEqualTo(NotificationException.Reason.UNAUTHENTICATED));
    }

    @Test void deletionAndInvalidationCannotUndoAReplacementRegistration() throws Exception {
        Device original = device("replacement-race", "a");
        Device replacement = device("replacement-race", "b");
        register(original);
        assertThat(winners(List.of(
                () -> tryRegister(replacement),
                () -> db.tx(em -> {
                    store(em).deactivateDevice(original.id(), original.tokenHash(), original.tokenCiphertext());
                    return true;
                })))).isEqualTo(2);
        Device refreshed = db.tx(em -> store(em).findDevice(original.deviceId()));
        assertThat(refreshed.active()).isTrue();
        assertThat(refreshed.tokenHash()).isEqualTo(replacement.tokenHash());

        assertThat(winners(List.of(
                () -> tryRegister(replacement),
                () -> db.tx(em -> {
                    store(em).deleteDevice(member, original.deviceId());
                    return true;
                })))).isEqualTo(2);
        Device remaining = db.tx(em -> store(em).findDevice(original.deviceId()));
        if (remaining != null) {
            assertThat(remaining.active()).isTrue();
            assertThat(remaining.tokenHash()).isEqualTo(replacement.tokenHash());
        }
        assertThat(db.count("device_token")).isBetween(0L, 1L);
    }

    @Test void concurrentDeliveryClaimsHaveOneWinnerForTheSameEventOrdinalAndAttempt() throws Exception {
        Device device = device("device-a","a"); Event event = event("INITIAL",now);
        db.tx(em -> { store(em).upsertDevice(device); assertThat(store(em).claimEvent(event)).isTrue(); return null; });
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) calls.add(() -> db.tx(em -> store(em).beginDelivery(delivery(event,device))));
        assertThat(winners(calls)).isEqualTo(1);
        assertThat(db.count("notification_delivery")).isEqualTo(1);
    }

    @Test void conditionalInvalidationCannotDeactivateARotatedToken() {
        Device previous = device("device-a","a");
        Device rotated = new Device(previous.id(),member,previous.deviceId(),"b".repeat(64),"test-new-ciphertext",Platform.ANDROID,true,now.plusSeconds(1));
        db.tx(em -> { store(em).upsertDevice(previous); return null; });
        db.tx(em -> { store(em).upsertDevice(rotated); return null; });
        db.tx(em -> { store(em).deactivateDevice(previous.id(),previous.tokenHash(),previous.tokenCiphertext()); return null; });
        Device current = db.tx(em -> store(em).findDevice(previous.deviceId()));
        assertThat(current.active()).isTrue(); assertThat(current.tokenHash()).isEqualTo(rotated.tokenHash());
        db.tx(em -> { store(em).deactivateDevice(current.id(),current.tokenHash(),current.tokenCiphertext()); return null; });
        assertThat(db.<Device>tx(em -> store(em).findDevice(previous.deviceId())).active()).isFalse();
    }

    @Test void uniqueTokenViolationReturns409AndRollsBackWithoutChangingTheOriginalToken() {
        Device first = device("device-a","a"), second = device("device-b","b");
        db.tx(em -> { store(em).upsertDevice(first); store(em).upsertDevice(second); return null; });
        Device conflicting = new Device(first.id(),member,first.deviceId(),second.tokenHash(),"test-conflicting-ciphertext",Platform.IOS,true,now.plusSeconds(1));
        assertThatThrownBy(() -> db.tx(em -> { store(em).upsertDevice(conflicting); return null; }))
                .isInstanceOfSatisfying(NotificationException.class,failure -> {
                    assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEVICE_TOKEN_CONFLICT);
                    assertThat(failure.status()).isEqualTo(409);
                });
        Device retained = db.tx(em -> store(em).findDevice(first.deviceId()));
        assertThat(retained.tokenHash()).isEqualTo(first.tokenHash());
        assertThat(retained.tokenCiphertext()).isEqualTo(first.tokenCiphertext());
        assertThat(db.count("device_token")).isEqualTo(2);
    }

    @Test void expiryDoesNotDeleteANewExpiredEventCommittedAfterChildrenWereDeleted() throws Exception {
        Device device = device("device-a","a"); Event lockedEvent = event("INITIAL",now);
        Event laterCommitted = event("SEVERITY:WARNING",now.minus(Duration.ofDays(31)));
        db.tx(em -> { store(em).upsertDevice(device); store(em).claimEvent(lockedEvent); store(em).beginDelivery(delivery(lockedEvent,device)); return null; });
        AtomicBoolean inserted = new AtomicBoolean();
        try (var concurrentCommit = Executors.newSingleThreadExecutor()) {
            int removed = db.tx(em -> store(afterDeliveryDelete(em,() -> {
                if (!inserted.compareAndSet(false,true)) return;
                try {
                    concurrentCommit.submit(() -> db.tx(other -> {
                        assertThat(store(other).claimEvent(laterCommitted)).isTrue();
                        assertThat(store(other).beginDelivery(delivery(laterCommitted,device))).isTrue();
                        return null;
                    })).get(10,TimeUnit.SECONDS);
                } catch (Exception failure) { throw new IllegalStateException("Concurrent disposable audit commit failed.",failure); }
            })).deleteExpiredEvents(now.plus(Duration.ofDays(30))));
            assertThat(removed).isEqualTo(1);
        }
        assertThat(inserted).isTrue();
        assertThat(db.count("notification_event")).isEqualTo(1);
        assertThat(db.count("notification_delivery")).isEqualTo(1);
        assertThat(db.<Event>tx(em -> store(em).findEvent(laterCommitted.id()))).isNotNull();
    }

    @Test void withdrawalAndLateFinishKeepHistoryAnonymousAndItsOriginalDeadline() {
        Device device = device("device-a","a"); Event event = event("INITIAL",now); Delivery pending = delivery(event,device);
        db.tx(em -> {
            var store = store(em); store.initializeSettings(member,now);
            store.saveSettings(member,new Settings(true,severity,true,now));
            store.upsertDevice(device); store.claimEvent(event); store.beginDelivery(pending); return null;
        });
        db.tx(em -> {
            em.createNativeQuery("UPDATE member SET status='WITHDRAWAL_PENDING' WHERE member_id=:id").setParameter("id",member).executeUpdate();
            store(em).withdrawMember(member,now.plusSeconds(1)); return null;
        });
        db.tx(em -> { store(em).finishDelivery(new Delivery(pending.id(),event.id(),device.id(),(short)1,(short)1,now,DeliveryResult.SUCCESS,"test-provider-response",null)); return null; });
        Event anonymous = db.tx(em -> store(em).findEvent(event.id()));
        assertThat(anonymous.memberId()).isNull(); assertThat(anonymous.retentionUntil()).isEqualTo(event.retentionUntil());
        assertThat(anonymous.deidentifiedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(db.<List<Delivery>>tx(em -> store(em).findDeliveries(event.id()))).singleElement().satisfies(delivery -> {
            assertThat(delivery.deviceTokenId()).isNull(); assertThat(delivery.providerMessageId()).isNull();
            assertThat(delivery.result()).isEqualTo(DeliveryResult.SUCCESS);
        });
        assertThat(db.count("device_token")).isZero();
        assertThat(db.<Settings>tx(em -> store(em).findSettings(member)).enabled()).isTrue();
        assertThat(db.<Boolean>tx(em -> store(em).beginDelivery(delivery(event,device)))).isFalse();
    }

    private JpaNotificationStore store(EntityManager em) {
        return new JpaNotificationStore(em,db.repository(em,NotificationSettingRepository.class),
                db.repository(em,NotificationDeviceRepository.class),db.repository(em,NotificationEventRepository.class),
                db.repository(em,NotificationDeliveryRepository.class));
    }
    private Event event(String key,Instant createdAt) {
        return new Event(UUID.randomUUID(),member,disaster,key,key.equals("INITIAL") ? EventKind.INITIAL : EventKind.SEVERITY_INCREASE,
                severity,"test official title",createdAt,createdAt.plus(Duration.ofDays(30)),null);
    }
    private Device device(String installation,String hashCharacter) {
        return new Device(UUID.randomUUID(),member,installation,hashCharacter.repeat(64),"test-encrypted-" + installation,Platform.IOS,true,now);
    }
    private void register(Device device) {
        db.tx(em -> { store(em).upsertDevice(device); return null; });
    }
    private boolean tryRegister(Device device) {
        try {
            register(device);
            return true;
        } catch (NotificationException failure) {
            assertThat(failure.reason()).isEqualTo(NotificationException.Reason.DEVICE_LIMIT_EXCEEDED);
            return false;
        }
    }
    private Delivery delivery(Event event,Device device) {
        return new Delivery(UUID.randomUUID(),event.id(),device.id(),(short)1,(short)1,null,DeliveryResult.PENDING,null,null);
    }
    private static int winners(List<Callable<Boolean>> calls) throws Exception {
        try (var workers = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var call : calls) futures.add(workers.submit(call));
            int count = 0; for (var future : futures) if (future.get(15,TimeUnit.SECONDS)) count++;
            return count;
        }
    }
    private static EntityManager afterDeliveryDelete(EntityManager delegate,Runnable concurrentCommit) {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),new Class<?>[]{EntityManager.class},(proxy,method,args) -> {
            Object result = invoke(delegate,method,args);
            if (method.getName().equals("createNativeQuery") && args[0] instanceof String sql && sql.startsWith("DELETE FROM notification_delivery")) {
                Query realQuery = (Query) result;
                return Proxy.newProxyInstance(Query.class.getClassLoader(),new Class<?>[]{Query.class},(queryProxy,queryMethod,queryArgs) -> {
                    Object outcome = invoke(realQuery,queryMethod,queryArgs);
                    if (queryMethod.getName().equals("executeUpdate")) concurrentCommit.run();
                    return outcome;
                });
            }
            return result;
        });
    }
    private static Object invoke(Object target,java.lang.reflect.Method method,Object[] arguments) throws Throwable {
        try { return method.invoke(target,arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}

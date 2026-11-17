package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.dashboard.PostgresAuditSupport;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.SettingsRequest;
import com.disasterinfo.eodiisseo.notification.persistence.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL member locking and partial updates in an explicitly disposable audit schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationSettingsPatchPostgresTest {
    private PostgresAuditSupport db;
    private final UUID member = UUID.randomUUID();
    private final MemberSession session = new MemberSession(member, UUID.randomUUID());

    @BeforeAll void openDatabase() throws Exception { db = new PostgresAuditSupport(); }
    @AfterAll void closeDatabase() throws Exception { if (db != null) db.close(); }

    @Test @Timeout(30)
    void concurrentPatchesReadAfterTheMemberLockAndPreserveBothIndependentChanges() throws Exception {
        db.sql("INSERT INTO member VALUES ('" + member + "','ACTIVE')");
        db.tx(em -> { store(em, () -> {}).initializeSettings(member, Instant.now()); return null; });
        var firstRead = new CountDownLatch(1); var releaseFirst = new CountDownLatch(1);
        var secondAttempt = new CountDownLatch(1); var secondRead = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> db.tx(em -> service(store(em, () -> {
                firstRead.countDown(); await(releaseFirst);
            })).patchSettings(session, new SettingsRequest(true, null, null, null), Set.of("enabled"))));
            try {
                assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> {
                    secondAttempt.countDown();
                    return db.tx(em -> service(store(em, secondRead::countDown)).patchSettings(session,
                            new SettingsRequest(null, null, null, true), Set.of("nightNotificationEnabled")));
                });
                assertThat(secondAttempt.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(secondRead.await(200, TimeUnit.MILLISECONDS)).isFalse();
                releaseFirst.countDown();
                first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            } finally { releaseFirst.countDown(); }
        }
        Settings finalSettings = db.tx(em -> store(em, () -> {}).findSettings(member));
        assertThat(finalSettings.enabled()).isTrue();
        assertThat(finalSettings.allowAfter22()).isTrue();
        assertThat(finalSettings.minimumSeverityId()).isNull();
    }

    private JpaNotificationStore store(EntityManager em, Runnable afterLockedRead) {
        return new JpaNotificationStore(em, db.repository(em, NotificationSettingRepository.class),
                db.repository(em, NotificationDeviceRepository.class), db.repository(em, NotificationEventRepository.class),
                db.repository(em, NotificationDeliveryRepository.class)) {
            @Override public Settings findSettingsForUpdate(UUID id) {
                Settings value = super.findSettingsForUpdate(id);
                afterLockedRead.run(); return value;
            }
        };
    }

    private NotificationService service(Store store) {
        Authentication authentication = new Authentication() {
            @Override public void requireActiveSession(MemberSession supplied) { assertThat(supplied).isEqualTo(session); }
            @Override public boolean isActiveMember(UUID id) { return member.equals(id); }
        };
        InterestAreas noSelectedAreas = new InterestAreas() {
            @Override public List<InterestArea> findOwned(UUID id) { return List.of(); }
            @Override public void replaceSelection(UUID id, Set<UUID> selected) { assertThat(selected).isEmpty(); }
            @Override public boolean isRelated(UUID disaster, UUID area) { return false; }
        };
        return new NotificationService(Optional.of(authentication), Optional.of(store), Optional.of(noSelectedAreas),
                Optional.empty(), Optional.empty());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock release timed out.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Test lock wait interrupted.", failure);
        }
    }
}

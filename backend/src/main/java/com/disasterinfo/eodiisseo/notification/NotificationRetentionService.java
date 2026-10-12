package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Called by real lifecycle/scheduler adapters, not exposed as a public member API. */
@Service
public class NotificationRetentionService {
    private final Optional<Store> store;
    public NotificationRetentionService(Optional<Store> store) { this.store = store; }
    @Transactional
    public void onMemberCreated(UUID memberId, Instant now) {
        if (memberId == null || now == null) throw NotificationService.invalid();
        NotificationService.connected(store).initializeSettings(memberId, now);
    }
    @Transactional
    public void onWithdrawal(UUID memberId, Instant now) {
        if (memberId == null || now == null) throw NotificationService.invalid();
        // Settings remain unchanged for seven-day recovery, tokens are never restored automatically.
        NotificationService.connected(store).withdrawMember(memberId, now);
    }
    @Transactional
    public void onRecoveryPeriodExpired(UUID memberId) {
        if (memberId == null) throw NotificationService.invalid();
        // Caller locks member and verifies WITHDRAWAL_PENDING plus the seven-day deadline.
        NotificationService.connected(store).deleteSettings(memberId);
    }
    @Transactional
    public int purgeExpired(Instant now) {
        if (now == null) throw NotificationService.invalid();
        return NotificationService.connected(store).deleteExpiredEvents(now);
    }
    @Transactional
    public void onDisasterDeleted(UUID disasterId) {
        if (disasterId == null) throw NotificationService.invalid();
        NotificationService.connected(store).unlinkDisaster(disasterId);
    }
}

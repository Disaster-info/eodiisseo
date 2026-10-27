package com.disasterinfo.eodiisseo.notification.persistence;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.NotificationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Enabled only after the confirmed PostgreSQL schema/migrations are supplied by the DB owner. */
@Repository
@ConditionalOnProperty(prefix = "eodiisseo.notification.persistence", name = "enabled", havingValue = "true")
@Transactional
public class JpaNotificationStore implements Store {
    private final EntityManager em;
    private final NotificationSettingRepository settings;
    private final NotificationDeviceRepository devices;
    private final NotificationEventRepository events;
    private final NotificationDeliveryRepository deliveries;
    public JpaNotificationStore(EntityManager em, NotificationSettingRepository settings,
            NotificationDeviceRepository devices, NotificationEventRepository events,
            NotificationDeliveryRepository deliveries) {
        this.em = em; this.settings = settings; this.devices = devices;
        this.events = events; this.deliveries = deliveries;
    }
    @Override public void initializeSettings(UUID memberId, Instant now) {
        requireActiveMemberLock(memberId);
        nativeQuery("INSERT INTO notification_setting(member_id,enabled,minimum_severity_id,allow_after_22,updated_at) VALUES (:member,false,NULL,false,:now) ON CONFLICT DO NOTHING", "member", memberId, "now", now).executeUpdate();
        em.clear();
    }
    @Override public Settings findSettings(UUID memberId) { return settings.findById(memberId).map(NotificationEntities.SettingRecord::value).orElse(null); }
    @Override public void saveSettings(UUID memberId, Settings value) {
        requireActiveMemberLock(memberId);
        settings.saveAndFlush(new NotificationEntities.SettingRecord(memberId, value));
    }
    @Override public List<UUID> findEnabledMembers() { return settings.findByEnabledTrue().stream().map(s -> s.memberId).toList(); }
    @Override public Device findDevice(String deviceId) { return devices.findByDeviceId(deviceId).map(NotificationEntities.DeviceRecord::value).orElse(null); }
    @Override public List<Device> findActiveDevices(UUID memberId) { return devices.findByMemberIdAndActiveTrue(memberId).stream().map(NotificationEntities.DeviceRecord::value).toList(); }

    @Override public void upsertDevice(Device d) {
        requireActiveMemberLock(d.memberId());
        // ON CONFLICT avoids a failed transaction while distinguishing installation ownership.
        int inserted = nativeQuery("""
                INSERT INTO device_token(device_token_id,member_id,device_id,token_hash,token_ciphertext,platform,active,updated_at)
                VALUES (:id,:member,:device,:hash,:cipher,:platform,true,:updated) ON CONFLICT DO NOTHING
                """, "id", d.id(), "member", d.memberId(), "device", d.deviceId(), "hash", d.tokenHash(),
                "cipher", d.tokenCiphertext(), "platform", d.platform().name(), "updated", d.updatedAt()).executeUpdate();
        if (inserted == 1) return;
        Device owner = findDevice(d.deviceId());
        if (owner == null) throw new NotificationException(NotificationException.Reason.DEVICE_TOKEN_CONFLICT);
        if (!owner.memberId().equals(d.memberId())) throw new NotificationException(NotificationException.Reason.DEVICE_OWNERSHIP_CONFLICT);
        try {
            int changed = nativeQuery("""
                    UPDATE device_token SET token_hash=:hash,token_ciphertext=:cipher,platform=:platform,active=true,updated_at=:updated
                    WHERE device_id=:device AND member_id=:member
                    """, "hash", d.tokenHash(), "cipher", d.tokenCiphertext(), "platform", d.platform().name(),
                    "updated", d.updatedAt(), "device", d.deviceId(), "member", d.memberId()).executeUpdate();
            if (changed != 1) throw new NotificationException(NotificationException.Reason.DEVICE_OWNERSHIP_CONFLICT);
        } catch (NotificationException safe) { throw safe; }
        catch (RuntimeException safe) {
            throw new NotificationException(isUniqueViolation(safe)
                    ? NotificationException.Reason.DEVICE_TOKEN_CONFLICT
                    : NotificationException.Reason.DEPENDENCY_UNAVAILABLE);
        }
        em.clear();
    }
    @Override public void deleteDevice(UUID memberId, String deviceId) {
        requireActiveMemberLock(memberId);
        // Member -> events -> device -> deliveries. beginDelivery cannot add references concurrently.
        nativeQuery("SELECT e.event_id FROM notification_event e WHERE e.event_id IN (SELECT n.event_id FROM notification_delivery n JOIN device_token d ON d.device_token_id=n.device_token_id WHERE d.member_id=:member AND d.device_id=:device) ORDER BY e.event_id FOR UPDATE", "member", memberId, "device", deviceId).getResultList();
        nativeQuery("SELECT device_token_id FROM device_token WHERE member_id=:member AND device_id=:device FOR UPDATE", "member", memberId, "device", deviceId).getResultList();
        nativeQuery("UPDATE notification_delivery SET device_token_id=NULL WHERE device_token_id IN (SELECT device_token_id FROM device_token WHERE member_id=:member AND device_id=:device)", "member", memberId, "device", deviceId).executeUpdate();
        nativeQuery("DELETE FROM device_token WHERE member_id=:member AND device_id=:device", "member", memberId, "device", deviceId).executeUpdate();
        em.clear();
    }
    @Override public void deactivateDevice(UUID id, String expectedTokenHash, String expectedTokenCiphertext) {
        List<?> owner = nativeQuery("SELECT member_id FROM device_token WHERE device_token_id=:id", "id", id).getResultList();
        if (owner.isEmpty() || !lockActiveMember(uuid(owner.getFirst()))) return;
        nativeQuery("UPDATE device_token SET active=false WHERE device_token_id=:id AND token_hash=:hash AND token_ciphertext=:cipher",
                "id", id, "hash", expectedTokenHash, "cipher", expectedTokenCiphertext).executeUpdate(); em.clear();
    }

    @Override public boolean claimEvent(Event e) {
        // The member owner also locks this row during withdrawal; no owned entity is duplicated.
        if (!lockActiveMember(e.memberId())) return false;
        return nativeQuery("""
                INSERT INTO notification_event(event_id,member_id,disaster_id,event_key,event_kind,severity_id,disaster_title_snapshot,created_at,retention_until,deidentified_at)
                VALUES (:id,:member,:disaster,:key,:kind,:severity,:title,:created,:retention,NULL) ON CONFLICT DO NOTHING
                """, "id", e.id(), "member", e.memberId(), "disaster", e.disasterId(), "key", e.eventKey(),
                "kind", e.kind().name(), "severity", e.severityId(), "title", e.titleSnapshot(),
                "created", e.createdAt(), "retention", e.retentionUntil()).executeUpdate() == 1;
    }
    @Override public Event findEvent(UUID id) { return events.findById(id).map(NotificationEntities.EventRecord::value).orElse(null); }
    @Override public List<Delivery> findDeliveries(UUID eventId) { return deliveries.findByEventId(eventId).stream().map(NotificationEntities.DeliveryRecord::value).toList(); }
    @Override public boolean beginDelivery(Delivery d) {
        List<?> owners = nativeQuery("SELECT member_id FROM notification_event WHERE event_id=:event", "event", d.eventId()).getResultList();
        if (owners.isEmpty() || owners.getFirst() == null) return false;
        UUID memberId = uuid(owners.getFirst());
        if (!lockActiveMember(memberId)) return false;
        List<?> lockedOwner = nativeQuery("SELECT member_id FROM notification_event WHERE event_id=:event FOR UPDATE", "event", d.eventId()).getResultList();
        if (lockedOwner.isEmpty() || lockedOwner.getFirst() == null || !memberId.equals(uuid(lockedOwner.getFirst()))) return false;
        if (d.deviceTokenId() != null && nativeQuery("SELECT device_token_id FROM device_token WHERE device_token_id=:device AND member_id=:member AND active=true FOR UPDATE", "device", d.deviceTokenId(), "member", memberId).getResultList().isEmpty()) return false;
        return nativeQuery("""
                INSERT INTO notification_delivery(delivery_id,event_id,device_token_id,target_ordinal,attempt_no,sent_at,result,provider_message_id,error_code)
                SELECT :id,e.event_id,:device,:ordinal,:attempt,NULL,'PENDING',NULL,NULL
                FROM notification_event e JOIN member m ON m.member_id=e.member_id
                WHERE e.event_id=:event AND m.status='ACTIVE' ON CONFLICT DO NOTHING
                """, "id", d.id(), "event", d.eventId(), "device", d.deviceTokenId(),
                "ordinal", d.targetOrdinal(), "attempt", d.attemptNo()).executeUpdate() == 1;
    }
    @Override public void finishDelivery(Delivery d) {
        // Establish the same member -> event -> delivery lock order as withdrawal.
        List<?> owners = nativeQuery("SELECT member_id FROM notification_event WHERE event_id=:event", "event", d.eventId()).getResultList();
        if (owners.isEmpty()) return;
        if (owners.getFirst() != null) lockMember(uuid(owners.getFirst()));
        List<?> lockedOwner = nativeQuery("SELECT member_id FROM notification_event WHERE event_id=:event FOR UPDATE", "event", d.eventId()).getResultList();
        if (lockedOwner.isEmpty()) return;
        String providerId = lockedOwner.getFirst() == null ? null : d.providerMessageId();
        nativeQuery("""
                UPDATE notification_delivery SET sent_at=:sent,result=:result,
                provider_message_id=:provider,error_code=:error
                WHERE delivery_id=:id AND event_id=:event AND result='PENDING'
                """, "sent", d.sentAt(), "result", d.result().name(), "provider", providerId,
                "error", d.errorCode(), "id", d.id(), "event", d.eventId()).executeUpdate();
        em.clear();
    }
    @Override public void withdrawMember(UUID memberId, Instant now) {
        lockMember(memberId);
        nativeQuery("SELECT event_id FROM notification_event WHERE member_id=:member ORDER BY event_id FOR UPDATE", "member", memberId).getResultList();
        nativeQuery("UPDATE notification_delivery SET device_token_id=NULL,provider_message_id=NULL WHERE event_id IN (SELECT event_id FROM notification_event WHERE member_id=:member)", "member", memberId).executeUpdate();
        nativeQuery("UPDATE notification_event SET member_id=NULL,deidentified_at=:now WHERE member_id=:member", "now", now, "member", memberId).executeUpdate();
        nativeQuery("DELETE FROM device_token WHERE member_id=:member", "member", memberId).executeUpdate();
        em.clear();
    }
    @Override public int deleteExpiredEvents(Instant now) {
        List<?> lockedEventIds = nativeQuery("SELECT event_id FROM notification_event WHERE retention_until<=:now ORDER BY event_id FOR UPDATE", "now", now).getResultList();
        List<UUID> expiredIds = lockedEventIds.stream().map(JpaNotificationStore::uuid).toList();
        if (expiredIds.isEmpty()) return 0;
        nativeQuery("DELETE FROM notification_delivery WHERE event_id IN (:ids)", "ids", expiredIds).executeUpdate();
        int count = nativeQuery("DELETE FROM notification_event WHERE event_id IN (:ids)", "ids", expiredIds).executeUpdate();
        em.clear(); return count;
    }
    @Override public void deleteSettings(UUID memberId) { lockMember(memberId); settings.deleteById(memberId); }
    @Override public void unlinkDisaster(UUID id) {
        nativeQuery("SELECT event_id FROM notification_event WHERE disaster_id=:id ORDER BY event_id FOR UPDATE", "id", id).getResultList();
        nativeQuery("UPDATE notification_event SET disaster_id=NULL WHERE disaster_id=:id", "id", id).executeUpdate(); em.clear();
    }
    private boolean lockActiveMember(UUID memberId) {
        return !nativeQuery("SELECT member_id FROM member WHERE member_id=:member AND status='ACTIVE' FOR UPDATE", "member", memberId).getResultList().isEmpty();
    }
    private void requireActiveMemberLock(UUID memberId) {
        if (!lockActiveMember(memberId)) throw new NotificationException(NotificationException.Reason.UNAUTHENTICATED);
    }
    private void lockMember(UUID memberId) {
        nativeQuery("SELECT member_id FROM member WHERE member_id=:member FOR UPDATE", "member", memberId).getResultList();
    }
    private static UUID uuid(Object value) { return value instanceof UUID id ? id : UUID.fromString(value.toString()); }
    private static boolean isUniqueViolation(Throwable failure) {
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return true;
        }
        return false;
    }
    private Query nativeQuery(String sql, Object... pairs) {
        Query query = em.createNativeQuery(sql);
        for (int i = 0; i < pairs.length; i += 2) query.setParameter((String) pairs[i], pairs[i + 1]);
        return query;
    }
}

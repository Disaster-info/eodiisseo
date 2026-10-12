package com.disasterinfo.eodiisseo.notification.persistence;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** ERD 4.2.1 owned tables only; other-domain foreign keys are UUID scalar references. */
public final class NotificationEntities {
    private NotificationEntities() {}

    @Entity(name = "NotificationSettingRecord")
    @Table(name = "notification_setting")
    public static class SettingRecord {
        @Id @Column(name = "member_id", nullable = false) UUID memberId;
        @Column(name = "enabled", nullable = false) boolean enabled;
        @Column(name = "minimum_severity_id") UUID minimumSeverityId;
        @Column(name = "allow_after_22", nullable = false) boolean allowAfter22;
        @Column(name = "updated_at", nullable = false) Instant updatedAt;
        protected SettingRecord() {}
        SettingRecord(UUID memberId, Settings s) {
            this.memberId = memberId; enabled = s.enabled(); minimumSeverityId = s.minimumSeverityId();
            allowAfter22 = s.allowAfter22(); updatedAt = s.updatedAt();
        }
        Settings value() { return new Settings(enabled, minimumSeverityId, allowAfter22, updatedAt); }
    }

    @Entity(name = "NotificationDeviceRecord")
    @Table(name = "device_token", uniqueConstraints = {
            @UniqueConstraint(columnNames = "device_id"), @UniqueConstraint(columnNames = "token_hash")})
    public static class DeviceRecord {
        @Id @Column(name = "device_token_id", nullable = false) UUID id;
        @Column(name = "member_id", nullable = false) UUID memberId;
        @Column(name = "device_id", nullable = false, length = 100) String deviceId;
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "char(64)") String tokenHash;
        @Column(name = "token_ciphertext", nullable = false, columnDefinition = "text") String tokenCiphertext;
        @Enumerated(EnumType.STRING) @Column(name = "platform", nullable = false, length = 10) Platform platform;
        @Column(name = "active", nullable = false) boolean active;
        @Column(name = "updated_at", nullable = false) Instant updatedAt;
        protected DeviceRecord() {}
        Device value() { return new Device(id, memberId, deviceId, tokenHash, tokenCiphertext, platform, active, updatedAt); }
    }

    @Entity(name = "NotificationEventRecord")
    @Table(name = "notification_event", uniqueConstraints =
            @UniqueConstraint(columnNames = {"member_id", "disaster_id", "event_key"}))
    public static class EventRecord {
        @Id @Column(name = "event_id", nullable = false) UUID id;
        @Column(name = "member_id") UUID memberId;
        @Column(name = "disaster_id") UUID disasterId;
        @Column(name = "event_key", nullable = false, length = 80) String eventKey;
        @Enumerated(EnumType.STRING) @Column(name = "event_kind", nullable = false, length = 20) EventKind kind;
        @Column(name = "severity_id") UUID severityId;
        @Column(name = "disaster_title_snapshot", nullable = false, length = 200) String titleSnapshot;
        @Column(name = "created_at", nullable = false) Instant createdAt;
        @Column(name = "retention_until", nullable = false) Instant retentionUntil;
        @Column(name = "deidentified_at") Instant deidentifiedAt;
        protected EventRecord() {}
        Event value() { return new Event(id, memberId, disasterId, eventKey, kind, severityId,
                titleSnapshot, createdAt, retentionUntil, deidentifiedAt); }
    }

    @Entity(name = "NotificationDeliveryRecord")
    @Table(name = "notification_delivery", uniqueConstraints =
            @UniqueConstraint(columnNames = {"event_id", "target_ordinal", "attempt_no"}))
    public static class DeliveryRecord {
        @Id @Column(name = "delivery_id", nullable = false) UUID id;
        @Column(name = "event_id", nullable = false) UUID eventId;
        @Column(name = "device_token_id") UUID deviceTokenId;
        @Column(name = "target_ordinal", nullable = false) short targetOrdinal;
        @Column(name = "attempt_no", nullable = false) short attemptNo;
        @Column(name = "sent_at") Instant sentAt;
        @Enumerated(EnumType.STRING) @Column(name = "result", nullable = false, length = 12) DeliveryResult result;
        @Column(name = "provider_message_id", length = 255) String providerMessageId;
        @Column(name = "error_code", length = 80) String errorCode;
        protected DeliveryRecord() {}
        Delivery value() { return new Delivery(id, eventId, deviceTokenId, targetOrdinal, attemptNo,
                sentAt, result, providerMessageId, errorCode); }
    }
}

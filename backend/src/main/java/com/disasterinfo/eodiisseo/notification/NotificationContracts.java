package com.disasterinfo.eodiisseo.notification;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Contracts for already verified authentication, official domain data and real infrastructure. */
public final class NotificationContracts {
    private NotificationContracts() {}
    /** Validate required text without changing opaque installation identifiers or token bytes. */
    static boolean blank(String text) {
        return text == null || text.codePoints().allMatch(code -> Character.isWhitespace(code) || Character.isSpaceChar(code));
    }
    /** Must be extracted from verified bearer authentication, never request parameters. */
    public record MemberSession(UUID memberId, UUID serviceSessionId) {}
    public interface Authentication {
        /** Recheck ACTIVE, session ownership, expiry and revocation on every request. */
        void requireActiveSession(MemberSession session);
        boolean isActiveMember(UUID memberId);
    }
    public record Severity(UUID id, String code, int rank) {
        public Severity {
            if (id == null || blank(code) || code.length() > 30
                    || rank < Short.MIN_VALUE || rank > Short.MAX_VALUE)
                throw new IllegalArgumentException("Invalid official severity contract.");
        }
    }
    public interface SeverityReader {
        Severity findByCode(String code);
        Severity findById(UUID id);
    }
    public record InterestArea(UUID id, boolean notifyEnabled) {}
    public interface InterestAreas {
        /** Only present, owned areas; withdrawn/deleted areas must be excluded. */
        List<InterestArea> findOwned(UUID memberId);
        /** Join the setting transaction; lock ACTIVE member first, then recheck area ownership/deletion. */
        void replaceSelection(UUID memberId, Set<UUID> selectedIds);
        /** Use existing official/GIS relevance, no duplicated spatial algorithm. */
        boolean isRelated(UUID disasterId, UUID interestAreaId);
    }
    public enum Platform { ANDROID, IOS }
    public record Settings(boolean enabled, UUID minimumSeverityId, boolean allowAfter22, Instant updatedAt) {}
    public record Device(UUID id, UUID memberId, String deviceId, String tokenHash,
                         String tokenCiphertext, Platform platform, boolean active, Instant updatedAt) {
        @Override public String toString() { return "Device[push credentials redacted]"; }
    }
    public interface TokenCipher {
        /** Production implementation must use managed keys and authenticated encryption. */
        String encrypt(String rawToken);
        String decrypt(String ciphertext);
    }
    public enum DisasterStatus { OCCURRED, ONGOING, ENDED, LIFTED }
    public record OfficialDisaster(UUID id, String type, String regionName, String title,
                                   String mainContent, Severity severity, DisasterStatus status,
                                   boolean official) {}
    public interface DisasterReader { OfficialDisaster findOfficial(UUID disasterId); }
    public enum ChangeKind { NEW, CHANGED }
    public record DisasterChange(UUID disasterId, ChangeKind kind, Severity previousSeverity) {}
    public enum EventKind { INITIAL, SEVERITY_INCREASE }
    public record Event(UUID id, UUID memberId, UUID disasterId, String eventKey, EventKind kind,
                        UUID severityId, String titleSnapshot, Instant createdAt,
                        Instant retentionUntil, Instant deidentifiedAt) {}
    public enum DeliveryResult { PENDING, SUCCESS, FAILED }
    public record Delivery(UUID id, UUID eventId, UUID deviceTokenId, short targetOrdinal,
                           short attemptNo, Instant sentAt, DeliveryResult result,
                           String providerMessageId, String errorCode) {}
    public record Push(UUID disasterId, String disasterType, String regionName,
                       String title, String content) {}
    public record PushResult(boolean successful, boolean invalidToken, String providerMessageId) {}
    public interface FcmSender {
        boolean isConfigured();
        /** The real adapter/lifecycle must fence withdrawal and registration changes during submission. */
        PushResult send(String rawToken, Push push);
    }
    public interface RetryPolicy {
        /** Explicit configured retry budget/time policy, never an invented default. */
        boolean mayRetry(Event event, Delivery failedAttempt, Instant now);
    }
    public enum TimeWindow { DAYTIME, NIGHT, UNRESOLVED }
    public interface NightPolicy {
        /** Classify Asia/Seoul time only under the agreed night end policy; unresolved hours remain held. */
        TimeWindow classify(Instant now);
    }
    public interface Store {
        /** Signup lifecycle only; confirmed ERD defaults, atomic insert without overwriting existing settings. */
        void initializeSettings(UUID memberId, Instant now);
        Settings findSettings(UUID memberId);
        void saveSettings(UUID memberId, Settings settings);
        List<UUID> findEnabledMembers();
        Device findDevice(String deviceId);
        List<Device> findActiveDevices(UUID memberId);
        void upsertDevice(Device device);
        void deleteDevice(UUID memberId, String deviceId);
        /** Invalidate only the registration submitted to FCM; never a concurrently rotated token. */
        void deactivateDevice(UUID deviceId, String expectedTokenHash, String expectedTokenCiphertext);
        /** Atomic insert-if-absent (member, disaster, event_key). */
        boolean claimEvent(Event event);
        Event findEvent(UUID eventId);
        List<Delivery> findDeliveries(UUID eventId);
        /** Atomic unique (event, ordinal, attempt), false if already claimed. */
        boolean beginDelivery(Delivery delivery);
        void finishDelivery(Delivery delivery);
        void withdrawMember(UUID memberId, Instant now);
        int deleteExpiredEvents(Instant now);
        void deleteSettings(UUID memberId);
        void unlinkDisaster(UUID disasterId);
    }
}

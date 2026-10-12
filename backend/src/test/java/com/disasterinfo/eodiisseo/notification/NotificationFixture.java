package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import java.time.Instant;
import java.util.*;

/** Test-only infrastructure; deliberately not a Spring component or runtime implementation. */
final class NotificationFixture {
    final UUID member = UUID.randomUUID(), sessionId = UUID.randomUUID(), area1 = UUID.randomUUID(), area2 = UUID.randomUUID(), disasterId = UUID.randomUUID();
    final MemberSession session = new MemberSession(member, sessionId);
    final Instant now = Instant.parse("2026-10-12T03:00:00Z");
    final Severity warning = new Severity(UUID.randomUUID(), "WARNING", 4);
    final Severity severe = new Severity(UUID.randomUUID(), "SEVERE", 8);
    final MemoryStore store = new MemoryStore();
    boolean active = true, revoked, expired, fcmConfigured = true, failFcm, invalidToken;
    int sends;
    OfficialDisaster disaster = new OfficialDisaster(disasterId, "HEAVY_RAIN", "test official region", "test official title", "test official content", warning, DisasterStatus.ONGOING, true);
    List<InterestArea> owned = new ArrayList<>(List.of(new InterestArea(area1, true), new InterestArea(area2, true)));
    final Authentication auth = new Authentication() {
        @Override public void requireActiveSession(MemberSession s) {
            if (!active || revoked || expired || !session.equals(s))
                throw new NotificationException(NotificationException.Reason.UNAUTHENTICATED);
        }
        @Override public boolean isActiveMember(UUID m) { return active && member.equals(m); }
    };
    final InterestAreas interests = new InterestAreas() {
        @Override public List<InterestArea> findOwned(UUID m) { return member.equals(m) ? List.copyOf(owned) : List.of(); }
        @Override public void replaceSelection(UUID m, Set<UUID> selected) {
            if (!member.equals(m) || !owned.stream().map(InterestArea::id).toList().containsAll(selected))
                throw new NotificationException(NotificationException.Reason.NOT_FOUND);
            owned = owned.stream().map(a -> new InterestArea(a.id(), selected.contains(a.id()))).toList();
        }
        @Override public boolean isRelated(UUID d, UUID a) { return disasterId.equals(d) && owned.stream().anyMatch(i -> i.id().equals(a)); }
    };
    final SeverityReader severityReader = new SeverityReader() {
        @Override public Severity findByCode(String code) { return List.of(warning, severe).stream().filter(s -> s.code().equals(code)).findFirst().orElse(null); }
        @Override public Severity findById(UUID id) { return List.of(warning, severe).stream().filter(s -> s.id().equals(id)).findFirst().orElse(null); }
    };
    final TokenCipher testCipher = new TokenCipher() {
        @Override public String encrypt(String raw) { return "TEST_ONLY_CIPHERTEXT:" + raw; }
        @Override public String decrypt(String raw) { return raw.substring("TEST_ONLY_CIPHERTEXT:".length()); }
    };
    final FcmSender testFcm = new FcmSender() {
        @Override public boolean isConfigured() { return fcmConfigured; }
        @Override public PushResult send(String raw, Push push) {
            sends++;
            if (failFcm) throw new IllegalStateException("test raw credential " + raw);
            return new PushResult(!invalidToken, invalidToken, "test-provider-message");
        }
    };
    NotificationFixture() { store.settings.put(member, new Settings(true, warning.id(), true, now)); }
    NotificationService service() { return new NotificationService(Optional.of(auth), Optional.of(store), Optional.of(interests), Optional.of(severityReader), Optional.of(testCipher)); }
    NotificationDispatchService dispatcher() {
        return new NotificationDispatchService(Optional.of(auth), Optional.of(store), Optional.of(interests), Optional.of(severityReader),
                Optional.of(id -> disasterId.equals(id) ? disaster : null), Optional.of(testCipher), Optional.of(testFcm),
                Optional.of((event, attempt, time) -> attempt.attemptNo() < 2), Optional.of(time -> TimeWindow.DAYTIME));
    }
    void device(String installation) { service().registerDevice(session, installation,
            new com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.DeviceRequest("test-token-" + installation, "ANDROID")); }

    static final class MemoryStore implements Store {
        final Map<UUID, Settings> settings = new HashMap<>();
        final Map<String, Device> devices = new LinkedHashMap<>();
        final Map<UUID, Event> events = new LinkedHashMap<>();
        final Map<UUID, Delivery> deliveries = new LinkedHashMap<>();
        @Override public synchronized void initializeSettings(UUID id, Instant now) { settings.putIfAbsent(id,new Settings(false,null,false,now)); }
        @Override public Settings findSettings(UUID id) { return settings.get(id); }
        @Override public void saveSettings(UUID id, Settings s) { settings.put(id, s); }
        @Override public List<UUID> findEnabledMembers() { return settings.entrySet().stream().filter(e -> e.getValue().enabled()).map(Map.Entry::getKey).toList(); }
        @Override public Device findDevice(String id) { return devices.get(id); }
        @Override public List<Device> findActiveDevices(UUID id) { return devices.values().stream().filter(d -> d.memberId().equals(id) && d.active()).toList(); }
        @Override public synchronized void upsertDevice(Device d) {
            Device old = devices.get(d.deviceId());
            if (old != null && !old.memberId().equals(d.memberId())) throw new NotificationException(NotificationException.Reason.DEVICE_OWNERSHIP_CONFLICT);
            if (devices.values().stream().anyMatch(existing -> !existing.deviceId().equals(d.deviceId()) && existing.tokenHash().equals(d.tokenHash())))
                throw new NotificationException(NotificationException.Reason.DEVICE_TOKEN_CONFLICT);
            devices.put(d.deviceId(), d);
        }
        @Override public void deleteDevice(UUID member, String device) {
            Device previous = devices.get(device);
            if (previous != null && previous.memberId().equals(member)) {
                devices.remove(device);
                deliveries.replaceAll((id, d) -> previous.id().equals(d.deviceTokenId())
                        ? new Delivery(id, d.eventId(), null, d.targetOrdinal(), d.attemptNo(), d.sentAt(), d.result(), d.providerMessageId(), d.errorCode()) : d);
            }
        }
        @Override public synchronized void deactivateDevice(UUID id, String expectedHash, String expectedCiphertext) {
            devices.replaceAll((key, d) -> d.id().equals(id) && Objects.equals(d.tokenHash(),expectedHash)
                    && Objects.equals(d.tokenCiphertext(),expectedCiphertext)
                    ? new Device(d.id(),d.memberId(),d.deviceId(),d.tokenHash(),d.tokenCiphertext(),d.platform(),false,d.updatedAt()) : d);
        }
        @Override public synchronized boolean claimEvent(Event e) {
            if (events.values().stream().anyMatch(old -> Objects.equals(old.memberId(), e.memberId()) && Objects.equals(old.disasterId(), e.disasterId()) && old.eventKey().equals(e.eventKey()))) return false;
            events.put(e.id(), e); return true;
        }
        @Override public Event findEvent(UUID id) { return events.get(id); }
        @Override public List<Delivery> findDeliveries(UUID id) { return deliveries.values().stream().filter(d -> d.eventId().equals(id)).toList(); }
        @Override public synchronized boolean beginDelivery(Delivery d) {
            if (deliveries.values().stream().anyMatch(old -> old.eventId().equals(d.eventId()) && old.targetOrdinal()==d.targetOrdinal() && old.attemptNo()==d.attemptNo())) return false;
            deliveries.put(d.id(), d); return true;
        }
        @Override public void finishDelivery(Delivery d) {
            Delivery existing=deliveries.get(d.id());
            if(existing==null || existing.result()!=DeliveryResult.PENDING) return;
            Event parent=events.get(d.eventId());
            deliveries.put(d.id(),new Delivery(d.id(),d.eventId(),existing.deviceTokenId(),d.targetOrdinal(),d.attemptNo(),
                    d.sentAt(),d.result(),parent==null || parent.memberId()==null ? null : d.providerMessageId(),d.errorCode()));
        }
        @Override public void withdrawMember(UUID member, Instant now) {
            Set<UUID> affected = new HashSet<>();
            events.replaceAll((id, e) -> {
                if (!member.equals(e.memberId())) return e;
                affected.add(id);
                return new Event(id, null, e.disasterId(), e.eventKey(), e.kind(), e.severityId(), e.titleSnapshot(), e.createdAt(), e.retentionUntil(), now);
            });
            deliveries.replaceAll((id,d) -> affected.contains(d.eventId()) ? new Delivery(id,d.eventId(),null,d.targetOrdinal(),d.attemptNo(),d.sentAt(),d.result(),null,d.errorCode()) : d);
            devices.values().removeIf(d -> member.equals(d.memberId()));
        }
        @Override public int deleteExpiredEvents(Instant now) {
            Set<UUID> expired = new HashSet<>(events.values().stream().filter(e -> !now.isBefore(e.retentionUntil())).map(Event::id).toList());
            deliveries.values().removeIf(d -> expired.contains(d.eventId())); expired.forEach(events::remove); return expired.size();
        }
        @Override public void deleteSettings(UUID id) { settings.remove(id); }
        @Override public void unlinkDisaster(UUID id) { events.replaceAll((key,e) -> id.equals(e.disasterId()) ? new Event(key,e.memberId(),null,e.eventKey(),e.kind(),e.severityId(),e.titleSnapshot(),e.createdAt(),e.retentionUntil(),e.deidentifiedAt()) : e); }
    }
}

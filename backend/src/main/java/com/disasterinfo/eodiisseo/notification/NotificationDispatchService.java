package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import static com.disasterinfo.eodiisseo.notification.NotificationContracts.blank;

/** Internal worker entry points. External collector/queue must call only after official DB commit. */
@Service
public class NotificationDispatchService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Optional<Authentication> authentication;
    private final Optional<Store> store;
    private final Optional<InterestAreas> areas;
    private final Optional<SeverityReader> severities;
    private final Optional<DisasterReader> disasters;
    private final Optional<TokenCipher> cipher;
    private final Optional<FcmSender> sender;
    private final Optional<RetryPolicy> retryPolicy;
    private final Optional<NightPolicy> nightPolicy;
    private final LongSupplier monotonicNanos;

    public NotificationDispatchService(Optional<Authentication> authentication, Optional<Store> store,
            Optional<InterestAreas> areas, Optional<SeverityReader> severities,
            Optional<DisasterReader> disasters, Optional<TokenCipher> cipher,
            Optional<FcmSender> sender, Optional<RetryPolicy> retryPolicy) {
        this(authentication, store, areas, severities, disasters, cipher, sender, retryPolicy, Optional.empty());
    }

    @Autowired
    public NotificationDispatchService(Optional<Authentication> authentication, Optional<Store> store,
            Optional<InterestAreas> areas, Optional<SeverityReader> severities,
            Optional<DisasterReader> disasters, Optional<TokenCipher> cipher,
            Optional<FcmSender> sender, Optional<RetryPolicy> retryPolicy, Optional<NightPolicy> nightPolicy) {
        this(authentication, store, areas, severities, disasters, cipher, sender, retryPolicy, nightPolicy, System::nanoTime);
    }

    NotificationDispatchService(Optional<Authentication> authentication, Optional<Store> store,
            Optional<InterestAreas> areas, Optional<SeverityReader> severities,
            Optional<DisasterReader> disasters, Optional<TokenCipher> cipher,
            Optional<FcmSender> sender, Optional<RetryPolicy> retryPolicy, Optional<NightPolicy> nightPolicy,
            LongSupplier monotonicNanos) {
        this.authentication = authentication; this.store = store; this.areas = areas;
        this.severities = severities; this.disasters = disasters; this.cipher = cipher;
        this.sender = sender; this.retryPolicy = retryPolicy; this.nightPolicy = nightPolicy;
        this.monotonicNanos = monotonicNanos;
    }

    public record DispatchReport(int createdEvents, int successfulDevices, int failedDevices,
                                 int skippedMembers, int internalFailures, boolean connected, Map<String,Integer> suppressionReasons) {
        public DispatchReport { suppressionReasons=Map.copyOf(suppressionReasons); }
        public DispatchReport(int created,int successful,int failed,int skipped,int errors,boolean connected) {
            this(created,successful,failed,skipped,errors,connected,Map.of());
        }
    }

    /**
     * No thrown worker failure may be used to roll back official disaster collection/query.
     * now is the trusted server processing start, never an external source's event timestamp.
     */
    public DispatchReport process(DisasterChange change, Instant now) {
        if (change == null || change.disasterId() == null || change.kind() == null || now == null)
            return new DispatchReport(0, 0, 0, 0, 1, false);
        if (authentication.isEmpty() || store.isEmpty() || areas.isEmpty() || disasters.isEmpty())
            return new DispatchReport(0, 0, 0, 0, 0, false);
        DispatchTime time = new DispatchTime(now, monotonicNanos);
        int created = 0, success = 0, failed = 0, skipped = 0, errors = 0;
        Map<String,Integer> suppressed=new LinkedHashMap<>();
        try {
            OfficialDisaster disaster = disasters.get().findOfficial(change.disasterId());
            if (!usable(disaster) || !change.disasterId().equals(disaster.id()))
                return new DispatchReport(0, 0, 0, 0, 0, true,Map.of("DISASTER_NOT_SENDABLE",1));
            boolean increase = change.kind() == ChangeKind.CHANGED && disaster.severity() != null
                    && change.previousSeverity() != null
                    && disaster.severity().rank() > change.previousSeverity().rank();
            if (change.kind() != ChangeKind.NEW && !increase)
                return new DispatchReport(0, 0, 0, 0, 0, true);
            String key = increase ? (change.eventKey()==null?"SEVERITY:" + disaster.severity().code():change.eventKey()) : "INITIAL";
            if (key.length() > 80) return new DispatchReport(0, 0, 0, 0, 1, true);
            for (UUID memberId : store.get().findEnabledMembers()) {
                try {
                    String rejection=suppressionReason(memberId,disaster,time);
                    if(rejection!=null){suppressed.merge(rejection,1,Integer::sum);skipped++;continue;}
                    Event event = new Event(UUID.randomUUID(), memberId, disaster.id(), key,
                            increase ? EventKind.SEVERITY_INCREASE : EventKind.INITIAL,
                            disaster.severity() == null ? null : disaster.severity().id(), disaster.title(),
                            now, now.plus(Duration.ofDays(30)), null);
                    if (!store.get().claimEvent(event)) { skipped++; continue; }
                    created++;
                    List<Device> devices = store.get().findActiveDevices(memberId);
                    if (devices.isEmpty()) {
                        recordNoDevice(event); failed++; continue;
                    }
                    short ordinal = 0;
                    for (Device device : devices) {
                        if (ordinal == Short.MAX_VALUE) { errors++; break; }
                        DeliveryResult result = attempt(event, device, ++ordinal, (short) 1, disaster, time);
                        if (result == DeliveryResult.SUCCESS) success++; else if (result == DeliveryResult.FAILED) failed++;
                    }
                } catch (RuntimeException safeFailure) { errors++; }
            }
            return new DispatchReport(created, success, failed, skipped, errors, true,suppressed);
        } catch (RuntimeException safeFailure) {
            return new DispatchReport(created, success, failed, skipped, errors + 1, true,suppressed);
        }
    }

    /** No automatic retry schedule/budget is fabricated when RetryPolicy is absent. */
    public DispatchReport retry(UUID eventId, Instant now) {
        if (store.isEmpty() || disasters.isEmpty() || retryPolicy.isEmpty() || authentication.isEmpty()
                || areas.isEmpty() || eventId == null || now == null)
            return new DispatchReport(0, 0, 0, 0, 0, false);
        int success = 0, failed = 0;
        DispatchTime time = new DispatchTime(now, monotonicNanos);
        try {
            Event event = store.get().findEvent(eventId);
            if (event == null || event.memberId() == null || event.disasterId() == null
                    || !time.current().isBefore(event.retentionUntil()))
                return new DispatchReport(0, 0, 0, 1, 0, true);
            OfficialDisaster disaster = disasters.get().findOfficial(event.disasterId());
            if (!usable(disaster) || !event.disasterId().equals(disaster.id()) || !eligible(event.memberId(), disaster, time))
                return new DispatchReport(0, 0, 0, 1, 0, true);
            Map<Short, Delivery> latest = new LinkedHashMap<>();
            store.get().findDeliveries(eventId).stream().sorted(Comparator.comparingInt(Delivery::attemptNo))
                    .forEach(delivery -> latest.put(delivery.targetOrdinal(), delivery));
            List<Device> active = store.get().findActiveDevices(event.memberId());
            for (Delivery previous : latest.values()) {
                if (previous.result() != DeliveryResult.FAILED || previous.deviceTokenId() == null
                        || acceptanceUnknown(previous) || previous.attemptNo() == Short.MAX_VALUE
                        || !retryPolicy.get().mayRetry(event, previous, time.current())) continue;
                Instant notBefore=store.get().retryNotBefore(previous.id());
                if(notBefore!=null && time.current().isBefore(notBefore))continue;
                Device current = active.stream().filter(d -> d.id().equals(previous.deviceTokenId())).findFirst().orElse(null);
                if (current == null) continue;
                DeliveryResult result = attempt(event, current, previous.targetOrdinal(),
                        (short) (previous.attemptNo() + 1), disaster, time);
                if (result == DeliveryResult.SUCCESS) success++; else if (result == DeliveryResult.FAILED) failed++;
            }
            return new DispatchReport(0, success, failed, 0, 0, true);
        } catch (RuntimeException safeFailure) { return new DispatchReport(0, success, failed, 0, 1, true); }
    }

    private boolean eligible(UUID memberId,OfficialDisaster disaster,DispatchTime time) {
        return suppressionReason(memberId,disaster,time)==null;
    }
    private String suppressionReason(UUID memberId,OfficialDisaster disaster,DispatchTime time) {
        if(!authentication.orElseThrow().isActiveMember(memberId))return "MEMBER_INACTIVE";
        Settings settings=store.orElseThrow().findSettings(memberId);
        if(settings==null || !settings.enabled())return "NOTIFICATION_OFF";
        if(!allowedTime(settings,time.current()))return "NIGHT_BLOCKED";
        if(settings.minimumSeverityId()!=null) {
            if(disaster.severity()==null || severities.isEmpty())return "MINIMUM_SEVERITY";
            Severity minimum=severities.get().findById(settings.minimumSeverityId());
            if(minimum==null || !settings.minimumSeverityId().equals(minimum.id()) || disaster.severity().rank()<minimum.rank())return "MINIMUM_SEVERITY";
        }
        boolean related=areas.orElseThrow().findOwned(memberId).stream().filter(InterestArea::notifyEnabled)
            .anyMatch(area->areas.orElseThrow().isRelated(disaster.id(),area.id()));
        if(!related)return "AREA_NOT_RELATED";
        return allowedTime(settings,time.current())?null:"NIGHT_BLOCKED";
    }

    private boolean allowedTime(Settings settings, Instant now) {
        if (settings.allowAfter22()) return true;
        // >=22 is confirmed; midnight does not implicitly reset the unconfirmed night period.
        return now.atZone(SEOUL).getHour() < 22 && nightPolicy.isPresent()
                && nightPolicy.get().classify(now) == TimeWindow.DAYTIME;
    }

    private DeliveryResult attempt(Event event, Device requestedDevice, short ordinal, short attemptNo,
                                   OfficialDisaster disaster, DispatchTime time) {
        Store data = store.orElseThrow();
        Device current = data.findDevice(requestedDevice.deviceId());
        if (current == null || !current.id().equals(requestedDevice.id()) || !current.active()
                || !current.memberId().equals(event.memberId()) || !time.current().isBefore(event.retentionUntil())
                || !eligible(event.memberId(), disaster, time))
            return null;
        Delivery pending = new Delivery(UUID.randomUUID(), event.id(), current.id(), ordinal,
                attemptNo, null, DeliveryResult.PENDING, null, null);
        if (!data.beginDelivery(pending)) return null;
        String error = null, providerId = null;
        Instant sentAt = null;
        boolean success = false;
        try {
            if (sender.isEmpty() || !sender.get().isConfigured()) error = "FCM_UNAVAILABLE";
            else if (cipher.isEmpty()) error = "TOKEN_CIPHER_UNAVAILABLE";
            else {
                String token = cipher.get().decrypt(current.tokenCiphertext());
                if (blank(token) || !NotificationService.hash(token).equals(current.tokenHash()))
                    error = "INVALID_DEVICE_TOKEN";
                else {
                    // Decryption may have taken time; respect unregister/rotation/withdrawal before FCM.
                    Device latest = data.findDevice(current.deviceId());
                    if (!authentication.orElseThrow().isActiveMember(event.memberId())) error = "MEMBER_INACTIVE";
                    else if (latest == null || !latest.active() || !latest.id().equals(current.id())
                            || !latest.memberId().equals(event.memberId()) || !latest.tokenHash().equals(current.tokenHash())
                            || !latest.tokenCiphertext().equals(current.tokenCiphertext()))
                        error = "DEVICE_REGISTRATION_CHANGED";
                    else if (!time.current().isBefore(event.retentionUntil())) error = "EVENT_EXPIRED";
                    else if (!eligible(event.memberId(), disaster, time)) error = "NOTIFICATION_SETTINGS_CHANGED";
                    else {
                        // The final area/GIS check is also an external call; it can race registration or withdrawal.
                        Device submission = data.findDevice(current.deviceId());
                        if (!authentication.orElseThrow().isActiveMember(event.memberId())) error = "MEMBER_INACTIVE";
                        else if (submission == null || !submission.active() || !submission.id().equals(current.id())
                                || !submission.memberId().equals(event.memberId()) || !submission.tokenHash().equals(current.tokenHash())
                                || !submission.tokenCiphertext().equals(current.tokenCiphertext()))
                            error = "DEVICE_REGISTRATION_CHANGED";
                        else {
                            Settings submissionSettings = data.findSettings(event.memberId());
                            Instant submissionTime = time.current();
                            if (!submissionTime.isBefore(event.retentionUntil())) error = "EVENT_EXPIRED";
                            else if (submissionSettings == null || !submissionSettings.enabled()
                                    || !allowedTime(submissionSettings,submissionTime)) error = "NOTIFICATION_SETTINGS_CHANGED";
                            else if(!data.authorizeSubmission(pending,current,submissionSettings,submissionTime,()->{
                                OfficialDisaster fresh=disasters.orElseThrow().findOfficial(event.disasterId());
                                return usable(fresh) && event.disasterId().equals(fresh.id()) && eligible(event.memberId(),fresh,time);
                            })) error="SUBMISSION_CANCELLED";
                            else {
                                sentAt = submissionTime;
                                PushResult result = sender.get().send(token, new Push(disaster.id(), disaster.type(),
                                        disaster.regionName(), disaster.title(), disaster.mainContent()));
                                if(result!=null && result.retryNotBefore()!=null) data.recordRetryNotBefore(pending.id(),result.retryNotBefore());
                                success = result != null && result.successful() && !result.invalidToken();
                                if (result != null && result.outcome() == PushOutcome.UNREGISTERED)
                                    data.deactivateDevice(current.id(), current.tokenHash(), current.tokenCiphertext());
                                if (!success) error = result == null ? PushOutcome.ACCEPTANCE_UNKNOWN.errorCode()
                                        : result.outcome().errorCode();
                                if (success && result.providerMessageId() != null && result.providerMessageId().length() <= 255
                                        && !result.providerMessageId().contains(token)) providerId = result.providerMessageId();
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException safeFailure) {
            // Once submission starts, a thrown sender error cannot prove that FCM rejected it.
            error = sentAt == null ? "FCM_SEND_FAILED" : PushOutcome.ACCEPTANCE_UNKNOWN.errorCode();
            success = false; providerId = null;
        }
        DeliveryResult outcome = success ? DeliveryResult.SUCCESS : DeliveryResult.FAILED;
        data.finishDelivery(new Delivery(pending.id(), pending.eventId(), pending.deviceTokenId(), ordinal,
                attemptNo, sentAt, outcome, providerId, error));
        return outcome;
    }

    private static boolean acceptanceUnknown(Delivery attempt) {
        return PushOutcome.NETWORK_TIMEOUT.errorCode().equals(attempt.errorCode())
                || PushOutcome.ACCEPTANCE_UNKNOWN.errorCode().equals(attempt.errorCode())
                // Existing generic failures predate acceptance classification; do not guess that
                // the provider rejected them after a process restart or deployment.
                || "FCM_SEND_FAILED".equals(attempt.errorCode());
    }

    private void recordNoDevice(Event event) {
        Delivery failure = new Delivery(UUID.randomUUID(), event.id(), null, (short) 1, (short) 1,
                null, DeliveryResult.PENDING, null, null);
        if (store.orElseThrow().beginDelivery(failure))
            store.orElseThrow().finishDelivery(new Delivery(failure.id(), event.id(), null, (short) 1,
                    (short) 1, null, DeliveryResult.FAILED, null, "NO_VALID_DEVICE"));
    }

    private static boolean usable(OfficialDisaster d) {
        return d != null && (d.status()==DisasterStatus.OCCURRED || d.status()==DisasterStatus.ONGOING) && d.official() && d.id() != null && !blank(d.type()) && !blank(d.regionName())
                && !blank(d.title()) && d.title().length() <= 200 && !blank(d.mainContent());
    }

    /** Caller-supplied entry time plus monotonic elapsed time; slow calls cannot reuse an old time window. */
    private static final class DispatchTime {
        private final Instant entryTime;
        private final LongSupplier monotonicNanos;
        private final long entryNanos;
        private DispatchTime(Instant entryTime, LongSupplier monotonicNanos) {
            this.entryTime = entryTime; this.monotonicNanos = monotonicNanos;
            entryNanos = monotonicNanos.getAsLong();
        }
        private Instant current() { return entryTime.plusNanos(Math.max(0, monotonicNanos.getAsLong() - entryNanos)); }
    }
}

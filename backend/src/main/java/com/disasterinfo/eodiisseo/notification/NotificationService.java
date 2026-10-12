package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.disasterinfo.eodiisseo.notification.NotificationContracts.blank;

@Service
public class NotificationService {
    private final Optional<Authentication> authentication;
    private final Optional<Store> store;
    private final Optional<InterestAreas> areas;
    private final Optional<SeverityReader> severities;
    private final Optional<TokenCipher> cipher;

    public NotificationService(Optional<Authentication> authentication, Optional<Store> store,
                               Optional<InterestAreas> areas, Optional<SeverityReader> severities,
                               Optional<TokenCipher> cipher) {
        this.authentication = authentication;
        this.store = store;
        this.areas = areas;
        this.severities = severities;
        this.cipher = cipher;
    }

    public SettingsResponse getSettings(MemberSession session) {
        requireAuthenticated(session);
        Settings settings = connected(store).findSettings(session.memberId());
        if (settings == null) throw unavailable();
        SettingsResponse result = response(session.memberId(), settings);
        requireAuthenticated(session);
        return result;
    }

    @Transactional
    public SettingsResponse updateSettings(MemberSession session, SettingsRequest request) {
        requireAuthenticated(session);
        if (request == null || request.enabled() == null || request.nightNotificationEnabled() == null
                || request.interestAreaIds() == null) throw invalid();
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : request.interestAreaIds()) {
            try {
                UUID id = UUID.fromString(raw);
                if (!id.toString().equalsIgnoreCase(raw)) throw invalid();
                ids.add(id);
            } catch (IllegalArgumentException | NullPointerException failure) { throw invalid(); }
        }
        Store data = connected(store);
        InterestAreas interests = connected(areas);
        Set<UUID> owned = new LinkedHashSet<>(interests.findOwned(session.memberId()).stream()
                .map(InterestArea::id).toList());
        if (!owned.containsAll(ids)) throw new NotificationException(NotificationException.Reason.NOT_FOUND);
        Severity minimum = request.minimumSeverity() == null ? null
                : connected(severities).findByCode(request.minimumSeverity());
        if (request.minimumSeverity() != null && minimum == null) throw invalid();
        Settings settings = new Settings(request.enabled(), minimum == null ? null : minimum.id(),
                request.nightNotificationEnabled(), Instant.now());
        // Area adapter must join this transaction and recheck ownership before changing notify_enabled.
        requireAuthenticated(session);
        interests.replaceSelection(session.memberId(), ids);
        requireAuthenticated(session);
        data.saveSettings(session.memberId(), settings);
        SettingsResponse result = response(session.memberId(), settings);
        requireAuthenticated(session);
        return result;
    }

    @Transactional
    public DeviceResponse registerDevice(MemberSession session, String deviceId, DeviceRequest request) {
        requireAuthenticated(session);
        if (blank(deviceId) || deviceId.length() > 100 || request == null || blank(request.fcmToken())) throw invalid();
        Platform platform;
        try { platform = Platform.valueOf(request.platform()); }
        catch (IllegalArgumentException | NullPointerException failure) { throw invalid(); }
        Store data = connected(store);
        Device existing = data.findDevice(deviceId);
        if (existing != null && !existing.memberId().equals(session.memberId()))
            throw new NotificationException(NotificationException.Reason.DEVICE_OWNERSHIP_CONFLICT);
        String encrypted;
        try { encrypted = connected(cipher).encrypt(request.fcmToken()); }
        catch (RuntimeException failure) { throw unavailable(); }
        if (blank(encrypted) || encrypted.equals(request.fcmToken())) throw unavailable();
        Device device = new Device(existing == null ? UUID.randomUUID() : existing.id(), session.memberId(),
                deviceId, hash(request.fcmToken()), encrypted, platform, true, Instant.now());
        requireAuthenticated(session);
        data.upsertDevice(device);
        requireAuthenticated(session);
        return new DeviceResponse(deviceId, true);
    }

    @Transactional
    public void unregisterDevice(MemberSession session, String deviceId) {
        requireAuthenticated(session);
        Store data = connected(store);
        Device device = data.findDevice(deviceId);
        if (device == null) throw new NotificationException(NotificationException.Reason.NOT_FOUND);
        if (!device.memberId().equals(session.memberId()))
            throw new NotificationException(NotificationException.Reason.FORBIDDEN);
        requireAuthenticated(session);
        data.deleteDevice(session.memberId(), deviceId);
        requireAuthenticated(session);
    }

    private SettingsResponse response(UUID memberId, Settings settings) {
        List<UUID> ids = connected(areas).findOwned(memberId).stream().filter(InterestArea::notifyEnabled)
                .map(InterestArea::id).toList();
        Severity minimum = settings.minimumSeverityId() == null ? null
                : connected(severities).findById(settings.minimumSeverityId());
        if (settings.minimumSeverityId() != null && minimum == null) throw unavailable();
        return new SettingsResponse(settings.enabled(), ids, minimum == null ? null : minimum.code(),
                settings.allowAfter22());
    }

    private void requireAuthenticated(MemberSession session) {
        if (session == null || session.memberId() == null || session.serviceSessionId() == null)
            throw new NotificationException(NotificationException.Reason.UNAUTHENTICATED);
        connected(authentication).requireActiveSession(session);
    }
    static <T> T connected(Optional<T> port) { return port.orElseThrow(NotificationService::unavailable); }
    static NotificationException unavailable() { return new NotificationException(NotificationException.Reason.DEPENDENCY_UNAVAILABLE); }
    static NotificationException invalid() { return new NotificationException(NotificationException.Reason.INVALID_REQUEST); }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable."); }
    }
}

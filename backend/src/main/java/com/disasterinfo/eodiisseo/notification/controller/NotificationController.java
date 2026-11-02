package com.disasterinfo.eodiisseo.notification.controller;

import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.MemberSession;
import com.disasterinfo.eodiisseo.notification.NotificationException;
import com.disasterinfo.eodiisseo.notification.NotificationService;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.DeviceRequest;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.DeviceResponse;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.SettingsRequest;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.SettingsResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Opt-in mappings; the owned HTTP startup guard requires the existing production auth chain. */
@RestController
@ConditionalOnProperty(name = "eodiisseo.owned-http.enabled", havingValue = "true")
public class NotificationController {
    private static final Set<String> SETTING_FIELDS = Set.of(
            "enabled", "interestAreaIds", "minimumSeverity", "nightNotificationEnabled");
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/v1/notification-settings")
    public SettingsResponse settings() {
        return notifications.getSettings(session());
    }

    @PatchMapping("/api/v1/notification-settings")
    public SettingsResponse patch(@RequestBody JsonNode body) {
        MemberSession session = session();
        Set<String> present = fields(body, SETTING_FIELDS);
        SettingsRequest values = new SettingsRequest(booleanValue(body, "enabled"), areaIds(body),
                textValue(body, "minimumSeverity", true), booleanValue(body, "nightNotificationEnabled"));
        return notifications.patchSettings(session, values, present);
    }

    @PutMapping("/api/v1/notification-devices/{deviceId}")
    public DeviceResponse register(@PathVariable("deviceId") String deviceId, @RequestBody JsonNode body) {
        MemberSession session = session();
        fields(body, Set.of("fcmToken", "platform"));
        return notifications.registerDevice(session, deviceId,
                new DeviceRequest(textValue(body, "fcmToken", false), textValue(body, "platform", false)));
    }

    @DeleteMapping("/api/v1/notification-devices/{deviceId}")
    public ResponseEntity<Void> unregister(@PathVariable("deviceId") String deviceId) {
        notifications.unregisterDevice(session(), deviceId);
        return ResponseEntity.noContent().build();
    }

    private static MemberSession session() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof MemberPrincipal principal)
                || principal.memberId() == null || principal.sessionId() == null) {
            throw new NotificationException(NotificationException.Reason.UNAUTHENTICATED);
        }
        return new MemberSession(principal.memberId(), principal.sessionId());
    }

    private static Set<String> fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw invalid();
        Set<String> fields = new HashSet<>(body.propertyNames());
        if (!allowed.containsAll(fields)) throw invalid();
        return fields;
    }

    private static Boolean booleanValue(JsonNode body, String field) {
        if (!body.has(field)) return null;
        JsonNode value = body.get(field);
        if (!value.isBoolean()) throw invalid();
        return value.asBoolean();
    }

    private static String textValue(JsonNode body, String field, boolean nullable) {
        if (!body.has(field)) return null;
        JsonNode value = body.get(field);
        if (value.isNull() && nullable) return null;
        if (!value.isString()) throw invalid();
        return value.asString();
    }

    private static List<String> areaIds(JsonNode body) {
        if (!body.has("interestAreaIds")) return null;
        JsonNode value = body.get("interestAreaIds");
        if (!value.isArray()) throw invalid();
        List<String> ids = new ArrayList<>();
        for (JsonNode id : value) {
            if (!id.isString()) throw invalid();
            ids.add(id.asString());
        }
        return ids;
    }

    private static NotificationException invalid() {
        return new NotificationException(NotificationException.Reason.INVALID_REQUEST);
    }
}

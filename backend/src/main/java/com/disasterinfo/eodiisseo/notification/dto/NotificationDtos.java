package com.disasterinfo.eodiisseo.notification.dto;

import java.util.List;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {}
    public record SettingsResponse(boolean enabled, List<UUID> interestAreaIds,
                                   String minimumSeverity, boolean nightNotificationEnabled) {
        public SettingsResponse { interestAreaIds = List.copyOf(interestAreaIds); }
    }
    /** Complete internal update command; partial PATCH/explicit-null decoding awaits HTTP contract agreement. */
    public record SettingsRequest(Boolean enabled, List<String> interestAreaIds,
                                  String minimumSeverity, Boolean nightNotificationEnabled) {}
    public record DeviceRequest(String fcmToken, String platform) {
        @Override public String toString() { return "DeviceRequest[push credentials redacted]"; }
    }
    public record DeviceResponse(String deviceId, boolean registered) {}
}

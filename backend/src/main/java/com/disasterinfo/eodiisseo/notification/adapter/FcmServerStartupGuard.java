package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.FcmSender;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Fail closed at Spring startup when FCM was explicitly enabled without a usable service
 * account. This checks local credential parsing only: Firebase permissions, API enablement,
 * device registration, and actual delivery still require separate integration checks.
 * No service-account paths, keys, or device tokens are logged or returned.
 */
@Component
@ConditionalOnProperty(name = "eodiisseo.fcm.enabled", havingValue = "true")
public final class FcmServerStartupGuard implements SmartInitializingSingleton {
    private final FcmSender sender;

    public FcmServerStartupGuard(FcmSender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!sender.isConfigured()) {
            throw new IllegalStateException(
                    "FCM is enabled but service-account credentials are missing or invalid. "
                    + "Set FCM_SERVICE_ACCOUNT_PATH to an existing Firebase service-account JSON file.");
        }
    }
}

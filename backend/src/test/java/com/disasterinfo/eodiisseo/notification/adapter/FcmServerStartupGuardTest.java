package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.FcmSender;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.Push;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.PushResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FcmServerStartupGuardTest {
    @Test
    void startupFailsWithoutUsableServiceAccount() {
        var guard = new FcmServerStartupGuard(sender(false));
        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FCM_SERVICE_ACCOUNT_PATH");
    }

    @Test
    void configuredSenderPassesStartupGuardWithoutSendingPush() {
        var guard = new FcmServerStartupGuard(sender(true));
        guard.afterSingletonsInstantiated();
    }

    @Test
    void guardIsRegisteredOnlyForExplicitFcmEnablement() {
        var condition = FcmServerStartupGuard.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.name()).containsExactly("eodiisseo.fcm.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
    }

    private static FcmSender sender(boolean configured) {
        return new FcmSender() {
            @Override public boolean isConfigured() { return configured; }
            @Override public PushResult send(String token, Push push) {
                throw new AssertionError("Startup must not attempt to send a push notification");
            }
        };
    }
}

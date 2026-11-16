package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.FcmSender;
import com.disasterinfo.eodiisseo.notification.NotificationDispatchService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FCM-only Spring wiring checks. Does not bootstrap DB/Flyway, contact Google,
 * or send a push. The credential-enabled test runs only if a local file exists.
 */
class FcmSpringWiringTest {
    // Test the actual @Component scan path, not direct registration of component classes.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FcmComponents.class, NotificationDispatchService.class);

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ServiceAccountFcmTokens.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {ServiceAccountFcmTokens.class, FcmHttpV1Sender.class,
                            FcmServerStartupGuard.class}))
    static class FcmComponents {}

    @Test
    void fcmBeansAreAbsentWhenDisabled() {
        // Explicitly override host environment variables (e.g. EODIISSEO_FCM_ENABLED=true)
        // so the disabled test is deterministic on developer machines and CI.
        contextRunner.withPropertyValues("eodiisseo.fcm.enabled=false").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBeansOfType(ServiceAccountFcmTokens.class)).isEmpty();
            assertThat(context.getBeansOfType(FcmSender.class)).isEmpty();
            assertThat(context.getBeansOfType(FcmServerStartupGuard.class)).isEmpty();
            assertThat(context.getBeansOfType(NotificationDispatchService.class)).hasSize(1);
        });
    }

    @Test
    void explicitlyEnabledFcmFailsClosedWithoutCredentials() {
        contextRunner.withPropertyValues("eodiisseo.fcm.enabled=true", "FCM_SERVICE_ACCOUNT_PATH=")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    // Spring can wrap SmartInitializingSingleton failures in different
                    // exception types. Assert on the actionable failure, not a wrapper type.
                    Throwable cause = context.getStartupFailure();
                    boolean missingCredentialsReported = false;
                    while (cause != null) {
                        if (cause.getMessage() != null
                                && cause.getMessage().contains("FCM_SERVICE_ACCOUNT_PATH")) {
                            missingCredentialsReported = true;
                            break;
                        }
                        cause = cause.getCause();
                    }
                    assertThat(missingCredentialsReported)
                            .as("Startup must report missing FCM credentials")
                            .isTrue();
                });
    }

    @Test
    void fcmSenderIsInjectedIntoDispatchWhenCredentialsArePresent() {
        String credentialPath = System.getenv("FCM_SERVICE_ACCOUNT_PATH");
        Assumptions.assumeTrue(credentialPath != null && !credentialPath.isBlank(),
                "Set FCM_SERVICE_ACCOUNT_PATH to run the real-credential wiring check");
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(credentialPath)),
                "Service-account JSON must exist on disk");

        contextRunner.withPropertyValues("eodiisseo.fcm.enabled=true",
                        "FCM_SERVICE_ACCOUNT_PATH=" + credentialPath)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    FcmSender sender = context.getBean(FcmSender.class);
                    assertThat(sender).isInstanceOf(FcmHttpV1Sender.class);
                    assertThat(sender.isConfigured()).isTrue();
                    Object injectedSender = ReflectionTestUtils.getField(
                            context.getBean(NotificationDispatchService.class), "sender");
                    assertThat(injectedSender).isEqualTo(Optional.of(sender));
                });
    }
}

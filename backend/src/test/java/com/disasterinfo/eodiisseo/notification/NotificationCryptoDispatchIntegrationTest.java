package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.notification.adapter.AesGcmNotificationTokenCipher;
import com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.DeviceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real AES-GCM, actual notification services, in-memory ports and fake FCM; no DB/network. */
class NotificationCryptoDispatchIntegrationTest {
    @TempDir Path temp;

    @Test void registrationStoresOnlyCiphertextAndDispatchUsesOriginalToken() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Path keyFile = temp.resolve("notification-key.txt");
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(secret), StandardCharsets.US_ASCII);
        java.util.Arrays.fill(secret, (byte) 0);
        var crypto = new AesGcmNotificationTokenCipher(keyFile.toString());
        var fixture = new NotificationFixture();
        String rawToken = "opaque-device-한글-\ud83d\udd10";
        var registration = new NotificationService(Optional.of(fixture.auth), Optional.of(fixture.store),
                Optional.of(fixture.interests), Optional.of(fixture.severityReader), Optional.of(crypto));
        registration.registerDevice(fixture.session, "integration-device", new DeviceRequest(rawToken, "ANDROID"));

        Device stored = fixture.store.findDevice("integration-device");
        assertThat(stored.tokenCiphertext()).startsWith("v1:").doesNotContain(rawToken);
        assertThat(stored.tokenHash()).isEqualTo(NotificationService.hash(rawToken));
        assertThat(crypto.decrypt(stored.tokenCiphertext())).isEqualTo(rawToken);

        AtomicReference<String> deliveredToken = new AtomicReference<>();
        FcmSender fakeFcm = new FcmSender() {
            @Override public boolean isConfigured() { return true; }
            @Override public PushResult send(String token, Push push) {
                deliveredToken.set(token);
                return new PushResult(true, false, "projects/test/messages/123");
            }
        };
        var dispatcher = new NotificationDispatchService(Optional.of(fixture.auth), Optional.of(fixture.store),
                Optional.of(fixture.interests), Optional.of(fixture.severityReader),
                Optional.of(id -> fixture.disasterId.equals(id) ? fixture.disaster : null),
                Optional.of(crypto), Optional.of(fakeFcm), Optional.empty(),
                Optional.of(now -> TimeWindow.DAYTIME));
        var result = dispatcher.process(new DisasterChange(fixture.disasterId, ChangeKind.NEW, null), fixture.now);

        assertThat(result.connected()).isTrue();
        assertThat(result.successfulDevices()).isEqualTo(1);
        assertThat(result.failedDevices()).isZero();
        assertThat(deliveredToken.get()).isEqualTo(rawToken);
        assertThat(fixture.store.deliveries.values()).extracting(Delivery::result).containsExactly(DeliveryResult.SUCCESS);
    }
}

package com.disasterinfo.eodiisseo.notification.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmNotificationTokenCipherTest {
    @TempDir Path temp;

    private Path keyFile(byte[] key) throws Exception {
        Path p = temp.resolve("token-key.txt");
        Files.writeString(p, Base64.getEncoder().encodeToString(key), StandardCharsets.US_ASCII);
        return p;
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test void encryptsAndDecryptsOpaqueUnicodeWithoutNormalization() throws Exception {
        Path path = keyFile(key());
        var crypto = new AesGcmNotificationTokenCipher(path.toString());
        String original = " \u00a0token-한글-\ud83d\udd10\n ";
        String encrypted = crypto.encrypt(original);
        assertThat(encrypted).startsWith("v1:").doesNotContain(original).doesNotContain("token-한글");
        assertThat(crypto.decrypt(encrypted)).isEqualTo(original);
        assertThat(new AesGcmNotificationTokenCipher(path.toString()).decrypt(encrypted)).isEqualTo(original);
    }

    @Test void sameTokenProducesDifferentCiphertexts() throws Exception {
        var crypto = new AesGcmNotificationTokenCipher(keyFile(key()).toString());
        assertThat(crypto.encrypt("same-token")).isNotEqualTo(crypto.encrypt("same-token"));
    }

    @Test void modificationAndWrongKeyFailClosed() throws Exception {
        var crypto = new AesGcmNotificationTokenCipher(keyFile(key()).toString());
        String encrypted = crypto.encrypt("private-device-token");
        byte[] payload = Base64.getUrlDecoder().decode(encrypted.substring(3));
        payload[payload.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        assertThatThrownBy(() -> crypto.decrypt(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid push token ciphertext.")
                .hasMessageNotContaining("private-device-token");
        assertThatThrownBy(() -> new AesGcmNotificationTokenCipher(key(), new SecureRandom()).decrypt(encrypted))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalidCiphertextRejectedWithoutFallback() throws Exception {
        var crypto = new AesGcmNotificationTokenCipher(keyFile(key()).toString());
        for (String invalid : Arrays.asList(null, "", "legacy:plaintext", "v1:", "v1:%%", "v1:AA")) {
            assertThatThrownBy(() -> crypto.decrypt(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> crypto.encrypt(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crypto.encrypt("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingOrMalformedKeyNeverEnablesPlaintextMode() throws Exception {
        assertThatThrownBy(() -> new AesGcmNotificationTokenCipher(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmNotificationTokenCipher(temp.resolve("missing").toString()))
                .isInstanceOf(IllegalStateException.class);
        Path badFile = temp.resolve("bad.key");
        Files.writeString(badFile, "this-is-not-a-valid-256-bit-key", StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> new AesGcmNotificationTokenCipher(badFile.toString()))
                .isInstanceOf(IllegalStateException.class);
    }
}

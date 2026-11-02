package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.TokenCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Authenticated encryption for opaque push tokens. Enabled only by an explicit feature flag.
 * The separate AES key must come from an external secret file, never from Firebase credentials.
 * Ciphertext format: v1:base64url(12-byte IV || AES-256-GCM ciphertext + 16-byte tag).
 *
 * Key replacement must be coordinated with device-token re-encryption or re-registration;
 * this version deliberately does not silently attempt unknown legacy keys.
 */
@Component
@ConditionalOnProperty(prefix = "eodiisseo.notification.crypto", name = "enabled", havingValue = "true")
public final class AesGcmNotificationTokenCipher implements TokenCipher {
    private static final String PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final int MAX_TOKEN_BYTES = 8_192;
    private static final int MAX_CIPHERTEXT_CHARS = 16_384;

    private final SecretKeySpec key;
    private final SecureRandom random;

    @Autowired
    public AesGcmNotificationTokenCipher(@Value("${EODIISSEO_NOTIFICATION_TOKEN_KEY_PATH:}") String path) {
        this(readKey(path), new SecureRandom());
    }

    AesGcmNotificationTokenCipher(byte[] rawKey, SecureRandom random) {
        if (rawKey == null || rawKey.length != KEY_BYTES || random == null)
            throw new IllegalStateException("Notification token encryption key is invalid.");
        try {
            this.key = new SecretKeySpec(rawKey, "AES");
        } finally {
            Arrays.fill(rawKey, (byte) 0);
        }
        this.random = random;
    }

    @Override
    public String encrypt(String rawToken) {
        if (rawToken == null || rawToken.isEmpty())
            throw new IllegalArgumentException("Push token is required.");
        byte[] plain = rawToken.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_TOKEN_BYTES)
            throw new IllegalArgumentException("Push token is too long.");
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] payload = new byte[IV_BYTES + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, IV_BYTES);
            System.arraycopy(encrypted, 0, payload, IV_BYTES, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Push token encryption failed.");
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX) || ciphertext.length() > MAX_CIPHERTEXT_CHARS)
            throw new IllegalArgumentException("Invalid push token ciphertext.");
        try {
            byte[] payload = Base64.getUrlDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (payload.length < IV_BYTES + TAG_BYTES + 1 || payload.length > IV_BYTES + TAG_BYTES + MAX_TOKEN_BYTES)
                throw new IllegalArgumentException("Invalid push token ciphertext.");
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            try {
                // Tokens are opaque strings; no trimming, Unicode normalization or case conversion.
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            throw new IllegalArgumentException("Invalid push token ciphertext.");
        }
    }

    private static byte[] readKey(String rawPath) {
        if (rawPath == null || rawPath.isBlank())
            throw new IllegalStateException("Notification token encryption is enabled but its key file is missing.");
        try {
            Path path = Path.of(rawPath);
            if (!Files.isRegularFile(path) || Files.size(path) > 256)
                throw new IllegalStateException("Notification token encryption key file is invalid.");
            String encoded = Files.readString(path, StandardCharsets.US_ASCII).trim();
            byte[] keyBytes = Base64.getDecoder().decode(encoded);
            if (keyBytes.length != KEY_BYTES)
                throw new IllegalStateException("Notification token encryption key must contain 32 bytes.");
            return keyBytes;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Notification token encryption key file is unavailable or invalid.");
        }
    }
}

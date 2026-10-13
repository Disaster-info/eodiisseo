package com.disasterinfo.eodiisseo.common.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 이름 기반 UUID v5 (RFC 4122/9562, SHA-1). 같은 이름이면 언제 만들어도 같은 UUID가 나온다.
 *
 * 왜 필요한가: 명세서는 내부 ID를 UUID로 정했는데, 아직 DB가 없어 저장 시 발급할 수 없다.
 * 원본 키(예: "SAFETYDATA:DSSP-IF-10943:1168000000:13")로 v5 UUID를 만들면 서버를 재시작해도 같은 대피소는
 * 같은 shelterId를 갖고, 나중에 DB에 저장할 때도 이 값을 그대로 쓸 수 있다.
 */
public final class UuidV5 {

    /** 이 프로젝트 전용 네임스페이스 (임의로 한 번 정한 고정값 — 바꾸면 모든 ID가 바뀌므로 변경 금지) */
    public static final UUID NAMESPACE = UUID.fromString("6f1c6b2e-3d7a-4c39-9a52-0e5b7d1f2a44");

    private UuidV5() {
    }

    public static UUID of(String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteBuffer ns = ByteBuffer.allocate(16)
                    .putLong(NAMESPACE.getMostSignificantBits())
                    .putLong(NAMESPACE.getLeastSignificantBits());
            sha1.update(ns.array());
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] h = sha1.digest();
            h[6] = (byte) ((h[6] & 0x0f) | 0x50); // 버전 5
            h[8] = (byte) ((h[8] & 0x3f) | 0x80); // RFC 변형
            ByteBuffer b = ByteBuffer.wrap(h, 0, 16);
            return new UUID(b.getLong(), b.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e); // 모든 JVM에 기본 포함이라 실제로는 발생하지 않음
        }
    }
}

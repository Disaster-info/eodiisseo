package com.disasterinfo.eodiisseo.ai.resource.entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** ERD 4.2.1 official_resource storage model. Changed sources use new IDs; citations remain immutable. */
public class OfficialResourceDocument {
    private final UUID resourceId;
    private final String agencyName;
    private final String title;
    private final String sourceUrl;
    private final String versionLabel;
    private final String contentText;
    private final Instant checkedAt;

    public OfficialResourceDocument(UUID resourceId, String agencyName, String title, String sourceUrl,
                                    String versionLabel, String contentText, Instant checkedAt) {
        this.resourceId = Objects.requireNonNull(resourceId);
        this.agencyName = required(agencyName, 100);
        this.title = required(title, 255);
        this.sourceUrl = required(sourceUrl, Integer.MAX_VALUE);
        if (versionLabel != null && versionLabel.length() > 100) throw new IllegalArgumentException("Invalid version label.");
        this.versionLabel = versionLabel;
        this.contentText = contentText;
        // PostgreSQL timestamptz persists microseconds; canonicalize before review fingerprinting.
        this.checkedAt = Objects.requireNonNull(checkedAt).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("Invalid official resource metadata.");
        return value;
    }

    /** Length-prefixed fields prevent ambiguous concatenation. The review binds every persisted field. */
    public String revisionFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{resourceId.toString(), agencyName, title, sourceUrl,
                    versionLabel, contentText, checkedAt.toString()}) {
                byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
                digest.update((field == null ? "-1:" : bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    public UUID getResourceId() { return resourceId; }
    public String getAgencyName() { return agencyName; }
    public String getTitle() { return title; }
    public String getSourceUrl() { return sourceUrl; }
    public String getVersionLabel() { return versionLabel; }
    public String getContentText() { return contentText; }
    public Instant getCheckedAt() { return checkedAt; }

    @Override public String toString() { return "OfficialResourceDocument[resourceId=" + resourceId + ", content=<redacted>]"; }
}

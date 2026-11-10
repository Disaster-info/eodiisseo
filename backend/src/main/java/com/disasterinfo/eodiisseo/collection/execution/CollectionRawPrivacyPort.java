package com.disasterinfo.eodiisseo.collection.execution;
/** Explicit reviewed field policy. UNKNOWN and detector failures omit bytes, never manufacture redacted originals. */
public interface CollectionRawPrivacyPort {
    enum Decision { ALLOW,OMIT,REVIEW_REQUIRED }
    String fingerprint();String reviewFingerprint();Decision inspect(String utf8);
}
package com.disasterinfo.eodiisseo.collection.operations;
import java.time.*;
import java.util.*;
import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort;


/** Reviewed local deployment evidence. No default/synthetic production implementation or remote I/O. */
public interface CollectionOperationalEvidencePort {
    enum Kind { LEGACY_FLEET, HTTP_SENDER, RAW_POLICY, DOMAIN_POLICY }
    record Evidence(Kind kind,String version,String fingerprint,String reference,Instant validUntil,boolean verified,boolean synthetic){
        public Evidence {Objects.requireNonNull(kind);Objects.requireNonNull(validUntil);if(version==null||version.isBlank()||fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Versioned evidence required");}
    }
    @FunctionalInterface interface Transport { CollectionProviderPort.Reply exchange(String contract, CollectionProviderPort.Request request); }
    Map<Kind,Evidence> current();
    default boolean legacyWorkersStopped(){return false;}
    default boolean legacySchedulersDisabled(){return false;}
    default boolean completionVerified(UUID run,String snapshot,String reference){return false;}
    /** Reviewed quota identity, time window and numeric limits; no guessed provider limits. */
    default boolean quotaApproved(String scope,Instant start,Instant end,long limit,int minInterval,String reference){return false;}
    /** Sender must be bounded, no transparent retries/redirects/proxy/credential logging. */
    Transport transport();
    default com.disasterinfo.eodiisseo.collection.execution.CollectionArchivePolicy archivePolicy(){return null;}
    /** Official page/window/snapshot/empty/incremental guarantees, not synthetic observations. */
    CollectionProviderPort.Evidence collectionContract(String contract);
}
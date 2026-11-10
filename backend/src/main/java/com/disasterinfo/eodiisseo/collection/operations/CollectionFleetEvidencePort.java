package com.disasterinfo.eodiisseo.collection.operations;
import java.time.Instant;
import java.util.*;
/** Local authoritative inventory/supervisor observation. Implementations must not perform remote I/O in DB transactions. */
public interface CollectionFleetEvidencePort {
    record Observation(Set<String> instances,String inventoryHash,String deploymentHash,String restartPolicyHash,
                       String pendingRequestsHash,boolean allStopped,boolean restartBlocked,boolean schedulersDisabled,
                       boolean completeInventory,Instant observedAt,Instant validUntil,boolean synthetic){
        public Observation {instances=Set.copyOf(instances);Objects.requireNonNull(observedAt);Objects.requireNonNull(validUntil);}
    }
    Optional<Observation> current();
}
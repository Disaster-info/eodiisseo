package com.disasterinfo.eodiisseo.disaster.projection;

import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim;
import java.time.*;
import java.util.*;

/** Independent local, reviewed domain contract. HTTP completeness evidence is never a domain grant.
 * No production implementation is registered. Implementations must use transactionally stable local evidence, never remote I/O.
 */
public interface ProjectionAdmissionPort {
    Optional<Grant> resolve(Claim claim,LocalDate from,LocalDate to,Instant asOf);
    record Grant(UUID target,String contract,UUID canonicalSource,String validationVersion,String reference,
                 LocalDate initialDate,Instant validUntil,boolean coverageApproved,boolean emptyApproved,boolean synthetic){
        public Grant {
            Objects.requireNonNull(target);Objects.requireNonNull(canonicalSource);Objects.requireNonNull(initialDate);Objects.requireNonNull(validUntil);
            if(contract==null||validationVersion==null||validationVersion.isBlank()||validationVersion.length()>200||reference==null||reference.isBlank())throw new IllegalArgumentException("Independent domain evidence required");
        }
    }
}
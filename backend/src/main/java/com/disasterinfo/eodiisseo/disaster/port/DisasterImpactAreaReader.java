package com.disasterinfo.eodiisseo.disaster.port;
import java.time.Instant;
import java.util.*;
/**
 * Explicit read boundary for Dashboard and the future InterestAreas owner adapter.
 * The caller must separately authenticate and obtain owned interest-area admin codes using its public Service.
 * This Port never queries member locations/interest_area tables and never accepts a member-supplied ownership assertion.
 */
public interface DisasterImpactAreaReader {
    Snapshot find(UUID disasterId,Instant asOf);
    enum Knowledge { VERIFIED, UNKNOWN }
    record Snapshot(Knowledge knowledge,Set<String> exactAdminAreaCodes,boolean activeIncident) {
        public Snapshot {exactAdminAreaCodes=Set.copyOf(exactAdminAreaCodes);}
        public static Snapshot unknown(){return new Snapshot(Knowledge.UNKNOWN,Set.of(),false);}
    }
}

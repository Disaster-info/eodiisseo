package com.disasterinfo.eodiisseo.disaster.port;
import java.net.URI;
import java.time.Instant;
import java.util.*;
/** Owner-supplied official crosswalk only. No production mapping/row is seeded by this module. */
public interface OfficialWarningAreaMappingPort {
    Optional<Mapping> resolve(String exactWarningZone,Instant warningIssuedAt,Instant asOf);
    record Mapping(String warningZone,Set<String> exactAdminAreaCodes,URI evidenceUrl,String version,
                   Instant validFrom,Instant validUntil,boolean completeCoverage) {
        public Mapping {exactAdminAreaCodes=exactAdminAreaCodes==null?Set.of():Set.copyOf(exactAdminAreaCodes);}
    }
}

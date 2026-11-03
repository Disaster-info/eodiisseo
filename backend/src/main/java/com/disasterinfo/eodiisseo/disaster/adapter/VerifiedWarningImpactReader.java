package com.disasterinfo.eodiisseo.disaster.adapter;
import com.disasterinfo.eodiisseo.disaster.port.*;
import com.disasterinfo.eodiisseo.disaster.service.VerifiedIncidentService;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import java.time.*;
import java.util.*;
/** Exact code crosswalk reader. Missing/revoked/expired/partial/mismatched evidence remains UNKNOWN. */
@Service @Profile("!test")
public class VerifiedWarningImpactReader implements DisasterImpactAreaReader {
    private final OwnedDatabase db;private final Optional<OfficialWarningAreaMappingPort> mappings;
    public VerifiedWarningImpactReader(OwnedDatabase db,Optional<OfficialWarningAreaMappingPort> mappings){this.db=db;this.mappings=mappings;}
    public Snapshot find(UUID disaster,Instant asOf){
        if(disaster==null||asOf==null||mappings.isEmpty())return Snapshot.unknown();
        var rows=db.rows("""
            select s.source_record_key,s.source_updated_at,d.status from source_disaster s join disaster d using(disaster_id)
            join disaster_type t using(disaster_type_id) join data_source p using(source_id)
            where d.disaster_id=? and d.data_origin='OFFICIAL' and t.code='HEAVY_RAIN'
            and p.endpoint_key='KMA_HEAVY_RAIN' and p.active
            """,disaster);
        // Competing authoritative records require explicit source selection, not a union or a guessed priority.
        if(rows.size()!=1)return Snapshot.unknown();
        var row=rows.getFirst();String key=(String)row.get("source_record_key");
        if(key==null||!key.contains(":"))return Snapshot.unknown();
        String zone=key.substring(0,key.indexOf(':'));Instant issued=OwnedDatabase.instant(row.get("source_updated_at"));
        if(issued==null||issued.isAfter(asOf))return Snapshot.unknown();
        OfficialWarningAreaMappingPort.Mapping mapping;
        try{var answer=mappings.get().resolve(zone,issued,asOf);mapping=answer==null?null:answer.orElse(null);}
        catch(RuntimeException unavailable){return Snapshot.unknown();}
        if(mapping==null||!zone.equals(mapping.warningZone())||!mapping.completeCoverage()||mapping.exactAdminAreaCodes().isEmpty()
            ||mapping.exactAdminAreaCodes().size()>1000||!VerifiedIncidentService.officialEvidence(mapping.evidenceUrl())
            ||mapping.version()==null||mapping.version().isBlank()||mapping.version().length()>200
            ||mapping.validFrom()==null||mapping.validUntil()==null||mapping.validFrom().isAfter(issued)
            ||mapping.validFrom().isAfter(asOf)||!mapping.validUntil().isAfter(asOf))return Snapshot.unknown();
        for(String code:mapping.exactAdminAreaCodes()){
            if(code==null||!code.matches("[A-Za-z0-9_-]{1,20}")||db.count("select count(*) from admin_area where area_code=? and active",code)!=1)return Snapshot.unknown();
        }
        return new Snapshot(Knowledge.VERIFIED,mapping.exactAdminAreaCodes(),Set.of("OCCURRED","ONGOING").contains(row.get("status")));
    }
}

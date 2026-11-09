package com.disasterinfo.eodiisseo.disaster.service;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.disaster.adapter.VerifiedWarningImpactReader;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Materializes verified warning impacts into the existing disaster-owned table consumed by the area owner. */
@Service @Profile("!test")
public class OfficialImpactAreaSynchronizer {
    private final OwnedDatabase db;private final VerifiedWarningImpactReader reader;
    public OfficialImpactAreaSynchronizer(OwnedDatabase db,VerifiedWarningImpactReader reader){this.db=db;this.reader=reader;}
    @Transactional
    public void refresh(UUID sourceRecord,Instant now){
        if(sourceRecord==null||now==null)throw new IllegalArgumentException("Source revision and processing time required");
        var row=db.one("""
            select s.source_disaster_id,s.disaster_id,r.revision_no,r.revision_id from source_disaster s
            join disaster d using(disaster_id) join disaster_type t using(disaster_type_id)
            join lateral(select revision_id,revision_no from source_disaster_revision where source_disaster_id=s.source_disaster_id order by revision_no desc limit 1) r on true
            where s.source_disaster_id=? and d.data_origin='OFFICIAL' and t.code='HEAVY_RAIN' for update of s,d
            """,sourceRecord);
        if(row==null)return;UUID disaster=(UUID)row.get("disaster_id");
        var evidence=reader.evidence(disaster,now);Set<String> desired=evidence.map(v->v.snapshot().exactAdminAreaCodes()).orElse(Set.of());
        var proof=new LinkedHashMap<String,Object>();proof.put("sourceRecord",sourceRecord.toString());proof.put("revisionId",row.get("revision_id").toString());
        proof.put("knowledge",evidence.isPresent()?"VERIFIED":"UNKNOWN");proof.put("sigunguCodes",desired.stream().sorted().toList());
        if(evidence.isPresent()){
            var value=evidence.get();var mapping=value.mapping();
            proof.put("warningZone",mapping.warningZone());proof.put("mappingVersion",mapping.version());proof.put("evidenceUrl",mapping.evidenceUrl().toString());
            proof.put("validFrom",mapping.validFrom().toString());proof.put("validUntil",mapping.validUntil().toString());
            proof.put("normalization",value.normalization().stream().map(m->Map.of("inputCode",m.input(),"sigunguCode",m.sigungu(),"catalogPath",m.catalogPath())).toList());
        }
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("officialImpactNormalization",proof));
        Set<String> previous=new TreeSet<>();db.rows("select area_code from disaster_area where source_disaster_id=? and area_role='IMPACT' and basis='OFFICIAL'",sourceRecord).forEach(a->previous.add((String)a.get("area_code")));
        boolean sameProof=db.count("select count(*) from disaster_change where disaster_id=? and new_value=?::jsonb",disaster,json)>0;
        if(previous.equals(desired)&&sameProof)return;
        db.update("delete from disaster_area where source_disaster_id=? and area_role='IMPACT' and basis='OFFICIAL'",sourceRecord);
        for(String code:new TreeSet<>(desired))db.update("insert into disaster_area(disaster_area_id,disaster_id,area_code,area_role,basis,source_disaster_id,derived_at) values(?,?,?,'IMPACT','OFFICIAL',?,?)",UUID.randomUUID(),disaster,code,sourceRecord,now);
        // Existing JSON audit storage preserves every input code, hierarchy, mapping version and validity without a schema change.
        if(!desired.isEmpty()||!previous.isEmpty()||evidence.isPresent())db.update("insert into disaster_change(change_id,disaster_id,change_kind,new_value,changed_at) values(?,?,'SOURCE_UPDATED',?::jsonb,?)",UUID.randomUUID(),disaster,json,now);
    }
}
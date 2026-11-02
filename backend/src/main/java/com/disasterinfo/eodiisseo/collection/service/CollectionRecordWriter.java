package com.disasterinfo.eodiisseo.collection.service;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import com.disasterinfo.eodiisseo.collection.domain.CollectedRecord;
import com.disasterinfo.eodiisseo.disaster.service.DisasterService;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component @Profile("!test")
public class CollectionRecordWriter {
    private final OwnedDatabase db;private final ObjectMapper json;private final DisasterService disasters;
    public CollectionRecordWriter(OwnedDatabase db,ObjectMapper json,DisasterService disasters){this.db=db;this.json=json;this.disasters=disasters;}
    public int save(UUID source,String endpoint,List<CollectedRecord> records,Instant now){
        for(var record:records){
            String raw=json.writeValueAsString(canonical(record.payload()));String hash=AuthService.hash(raw);
            var old=db.one("select record_id,content_hash from collection_raw_record where source_id=? and source_record_key=? for update",source,record.key());
            UUID id=old==null?UUID.randomUUID():(UUID)old.get("record_id");
            if(old==null)db.update("insert into collection_raw_record values(?,?,?,?::jsonb,?,?,?)",id,source,record.key(),raw,hash,now,now);
            else db.update("update collection_raw_record set payload=?::jsonb,content_hash=?,collected_at=? where record_id=?",raw,hash,now,id);
            if(old==null||!hash.equals(old.get("content_hash"))){
                int version=(int)db.count("select count(*) from collection_raw_revision where record_id=?",id)+1;
                db.update("insert into collection_raw_revision values(?,?,?::jsonb,?,?)",id,version,raw,hash,now);
            }
            if(endpoint.equals("KMA_EARTHQUAKE"))disasters.earthquake(source,record.key(),record.payload(),raw,now);
            if(endpoint.equals("SAFETYDATA_EARTHQUAKE_SHELTER"))shelter(source,record,raw,now);
        }
        return records.size();
    }
    private void shelter(UUID source,CollectedRecord record,String raw,Instant now){
        var p=record.payload();String name=Objects.toString(p.get("VT_ACMDFCLTY_NM"),"");
        if(name.isBlank()||name.length()>200)throw new IllegalArgumentException("Invalid shelter name");
        Double lat=number(p.get("LA")),lon=number(p.get("LO"));
        if((lat==null)!=(lon==null)||lat!=null&&(lat < -90||lat > 90||lon < -180||lon > 180))throw new IllegalArgumentException("Invalid shelter coordinates");
        Integer capacity=null;Object value=p.get("VT_ACMD_PSBL_NMPR");if(value!=null&&!value.toString().isBlank()){
            try{capacity=new java.math.BigDecimal(value.toString()).intValueExact();}catch(Exception e){throw new IllegalArgumentException("Invalid shelter capacity");}
            if(capacity<0)throw new IllegalArgumentException("Invalid shelter capacity");
        }
        String address=Objects.toString(p.get("RN_DTL_ADRES"),Objects.toString(p.get("EQK_ACMDFCLTY_ADRES"),null));
        db.update("insert into shelter(shelter_id,source_id,source_record_key,name,address,point,collected_at,additional_info,active,shelter_type,capacity) values(?,?,?,?,?,case when ?::double precision is null then null else ST_SetSRID(ST_MakePoint(?::double precision,?::double precision),4326)::geography end,?,?::jsonb,true,'EARTHQUAKE_OUTDOOR',?) on conflict(source_id,source_record_key) do update set name=excluded.name,address=excluded.address,point=excluded.point,collected_at=excluded.collected_at,additional_info=excluded.additional_info,active=true,capacity=excluded.capacity",UUID.randomUUID(),source,record.key(),name,address,lat,lon,lat,now,raw,capacity);
        // Partial or empty snapshots never delete/deactivate existing facilities.
    }
    private Double number(Object value){if(value==null||value.toString().isBlank())return null;try{double d=Double.parseDouble(value.toString());if(!Double.isFinite(d))throw new IllegalArgumentException();return d;}catch(Exception e){throw new IllegalArgumentException("Invalid coordinate");}}
    private Object canonical(Object value){if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted;}if(value instanceof List<?> list)return list.stream().map(this::canonical).toList();return value;}
}

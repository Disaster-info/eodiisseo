package com.disasterinfo.eodiisseo.disaster.service;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import java.time.*;
import java.time.format.*;
import java.util.*;

@Service @Profile("!test")
public class DisasterService {
    private final OwnedDatabase db;
    public DisasterService(OwnedDatabase db){this.db=db;}
    /** Called inside the collector transaction, only for a documented actual earthquake report. */
    public void earthquake(UUID source,String key,Map<String,Object> payload,String original,Instant collectedAt){
        Object occurred=payload.get("tmEqk");
        // Preliminary/other announcements lacking occurrence facts remain raw records.
        if(occurred==null||!occurred.toString().matches("[0-9]{14}"))return;
        Instant occurredAt;double lat,lon;
        try{
            occurredAt=LocalDateTime.parse(occurred.toString(),DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.of("Asia/Seoul")).toInstant();
            lat=Double.parseDouble(String.valueOf(payload.get("lat")));lon=Double.parseDouble(String.valueOf(payload.get("lon")));
        }catch(RuntimeException e){return;}
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -90||lat > 90||lon < -180||lon > 180||occurredAt.isAfter(collectedAt))return;
        var old=db.one("select * from source_disaster where source_id=? and source_record_key=? for update",source,key);
        String hash=AuthService.hash(original);String address=Objects.toString(payload.get("loc"),null);
        if(address!=null&&address.length()>180)address=address.substring(0,180);
        UUID disaster=old==null?UUID.randomUUID():(UUID)old.get("disaster_id");
        UUID record=old==null?UUID.randomUUID():(UUID)old.get("source_disaster_id");
        if(old!=null&&hash.equals(old.get("content_hash"))){db.update("update source_disaster set collected_at=? where source_disaster_id=?",collectedAt,record);return;}
        if(old==null){
            var type=db.one("select disaster_type_id from disaster_type where code='EARTHQUAKE' and active");
            if(type==null)throw new IllegalStateException("Earthquake catalog missing");
            db.update("insert into disaster(disaster_id,disaster_type_id,title,occurred_at,status,data_origin,updated_at) values(?,?,?,?,'OCCURRED','OFFICIAL',?)",disaster,type.get("disaster_type_id"),address==null?"기상청 지진정보":"기상청 지진정보: "+address,occurredAt,collectedAt);
            db.update("insert into source_disaster(source_disaster_id,disaster_id,source_id,external_id,source_record_key,source_address,source_url,original_text,collected_at,content_hash,occurrence_point) values(?,?,?,?,?,?,'https://www.weather.go.kr',?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326)::geography)",record,disaster,source,key,key,address,original,collectedAt,hash,lon,lat);
        }else{
            db.update("update source_disaster set original_text=?,source_address=?,collected_at=?,content_hash=?,occurrence_point=ST_SetSRID(ST_MakePoint(?,?),4326)::geography where source_disaster_id=?",original,address,collectedAt,hash,lon,lat,record);
            db.update("update disaster set occurred_at=?,updated_at=? where disaster_id=?",occurredAt,collectedAt,disaster);
        }
        int revision=(int)db.count("select count(*) from source_disaster_revision where source_disaster_id=?",record)+1;
        db.update("insert into source_disaster_revision(revision_id,source_disaster_id,revision_no,original_text,source_address,content_hash,observed_at) values(?,?,?,?,?,?,?)",UUID.randomUUID(),record,revision,original,address,hash,collectedAt);
        // Magnitude is not an official severity; epicenter is not an IMPACT polygon.
        db.update("insert into disaster_change(change_id,disaster_id,change_kind,new_value,changed_at) values(?,?,?,?::jsonb,?)",UUID.randomUUID(),disaster,old==null?"NEW":"SOURCE_UPDATED","{\"sourceRevision\":"+revision+"}",collectedAt);
    }
}

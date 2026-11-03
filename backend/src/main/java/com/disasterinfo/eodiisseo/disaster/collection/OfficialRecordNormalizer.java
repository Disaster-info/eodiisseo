package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import com.disasterinfo.eodiisseo.disaster.service.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.*;

/** Only documented facts enter the official disaster model; ambiguous reports remain archived. */
@Service @Profile("!test")
public class OfficialRecordNormalizer {
    private final OwnedDatabase db;private final DisasterService earthquakes;private final DisasterRuntimeEvents events;private final VerifiedIncidentService incidents;
    public OfficialRecordNormalizer(OwnedDatabase db,DisasterService earthquakes,DisasterRuntimeEvents events,VerifiedIncidentService incidents){this.db=db;this.earthquakes=earthquakes;this.events=events;this.incidents=incidents;}
    public record Result(String classification,UUID sourceRecord) {}
    public static Instant time(Object input){
        String text=Objects.toString(input,"");
        if(!text.matches("[0-9]{12}([0-9]{2})?"))throw new IllegalArgumentException("Invalid KST timestamp");
        try { return LocalDateTime.parse(text,DateTimeFormatter.ofPattern(text.length()==12?"uuuuMMddHHmm":"uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.of("Asia/Seoul")).toInstant(); } catch(DateTimeException e){throw new IllegalArgumentException("Invalid KST timestamp");}
    }
    public static String identity(OfficialProviderClient.Provider provider,Map<String,Object> row){
        if(provider==WILDFIRE_MESSAGES)return bounded(row.get("SN"));
        if(!bounded(row.get("stnId")).matches("[0-9]{1,5}")||!bounded(row.get("tmSeq")).matches("[0-9]{1,4}"))throw new IllegalArgumentException("Invalid announcement identity");
        return bounded(row.get("stnId"))+":"+bounded(row.get("tmFc"))+":"+bounded(row.get("tmSeq"))+
            (provider==HEAVY_RAIN?":"+bounded(row.get("areaCode")):"");
    }
    private static String bounded(Object value){
        String text=Objects.toString(value,"");if(text.isBlank()||text.length()>64)throw new IllegalArgumentException("Missing official identity");
        return text;
    }
    @Transactional
    public Result normalize(UUID source,OfficialProviderClient.Provider provider,Map<String,Object> row,String original,Instant now){
        if(db.count("select count(*) from data_source where source_id=? and active and source_kind='DISASTER' and endpoint_key=?",source,provider.endpointKey)!=1)
            throw new CollectionFailure("SOURCE_CONTRACT");
        if(provider==WILDFIRE_MESSAGES && !"산불".equals(row.get("DST_SE_NM")))return new Result("UNCLASSIFIED_MESSAGE",null);
        if(provider==WILDFIRE_MESSAGES||provider==EARTHQUAKE)return incidents.normalize(source,provider,row,original,now);
        try {
            String key=identity(provider,row);Instant issued=time(row.get("tmFc"));
            if(issued.isAfter(now))return new Result("FUTURE_REPORT",null);
            return rain(source,key,row,original,issued,now);
        }catch(IllegalArgumentException e){return new Result("INVALID_FACTS",null);}
    }
    private Result rain(UUID source,String announcement,Map<String,Object> row,String original,Instant issued,Instant now){
        if(!"2".equals(Objects.toString(row.get("warnVar"))))return new Result("NOT_HEAVY_RAIN",null);
        if(!"0".equals(Objects.toString(row.get("cancel"))))return new Result("CANCELLED_OR_UNKNOWN_REPORT",null);
        String zone=bounded(row.get("areaCode")),strength=Objects.toString(row.get("warnStress"));
        if(!zone.matches("[A-Za-z0-9_-]{1,64}"))return new Result("INVALID_WARNING_ZONE",null);
        String command=Objects.toString(row.get("command"));
        if(!Set.of("0","1","2").contains(strength)||!Set.of("1","2","3","6","7","8").contains(command))return new Result("UNKNOWN_WARNING_CODE",null);
        db.rows("select pg_advisory_xact_lock(hashtextextended(?,0))",source+":"+zone);
        // Exact official warning zone only. Never map by district names or substring similarity.
        var old=db.one("""
            select s.*,d.status,d.occurred_at from source_disaster s join disaster d using(disaster_id)
            where s.source_id=? and split_part(s.source_record_key,':' ,1)=? and d.data_origin='OFFICIAL'
            order by s.source_updated_at desc nulls last limit 1 for update of s,d
            """,source,zone);
        Instant start=null;try{start=time(row.get("startTime"));}catch(IllegalArgumentException ignored){}
        boolean lift=Set.of("2","8").contains(command);
        if(old!=null&&OwnedDatabase.instant(old.get("source_updated_at"))!=null&&issued.isBefore(OwnedDatabase.instant(old.get("source_updated_at"))))
            return new Result("OUT_OF_ORDER_WARNING",null);
        if("1".equals(command)&&start!=null){
            old=db.one("select s.*,d.status,d.occurred_at from source_disaster s join disaster d using(disaster_id) where s.source_id=? and s.source_record_key=? for update of s,d",source,zone+":"+start);
        }
        if(old==null&&(lift||start==null||!"1".equals(command)))return new Result("WARNING_PREDECESSOR_MISSING",null);
        if(old!=null&&Set.of("LIFTED","ENDED").contains(old.get("status"))&&!lift)return new Result("WARNING_PREDECESSOR_MISSING",null);
        if(start==null&&old!=null)start=OwnedDatabase.instant(old.get("occurred_at"));
        if(start==null||start.isAfter(now))return new Result("WARNING_NOT_YET_EFFECTIVE",null);
        Instant ended=lift?time(row.get("endTime")):null;
        if(ended!=null&&(ended.isBefore(start)||ended.isAfter(now)))return new Result("INVALID_LIFT_TIME",null);
        String hash=AuthService.hash(original);
        if(old!=null&&hash.equals(old.get("content_hash"))){
            db.update("update source_disaster set collected_at=? where source_disaster_id=?",now,old.get("source_disaster_id"));
            return new Result("HEAVY_RAIN_DUPLICATE",(UUID)old.get("source_disaster_id"));
        }
        UUID disaster=old==null?UUID.randomUUID():(UUID)old.get("disaster_id");
        UUID record=old==null?UUID.randomUUID():(UUID)old.get("source_disaster_id");
        String status=lift?"LIFTED":"ONGOING";
        // KMA guide 2026-06-01 adds 2=major warning. This is a source level, not a common severity mapping.
        String sourceStatus=switch(strength){case "0"->"WATCH";case "1"->"WARNING";case "2"->"MAJOR_WARNING";default->throw new IllegalArgumentException();};
        sourceStatus+=":"+command;
        String title="기상청 호우"+switch(strength){case "0"->"주의보";case "1"->"경보";case "2"->"중대경보";default->throw new IllegalArgumentException();}+" "+zone;
        if(old==null){
            var type=db.one("select disaster_type_id from disaster_type where code='HEAVY_RAIN' and active");
            if(type==null)throw new CollectionFailure("CATALOG_MISSING");
            db.update("insert into disaster(disaster_id,disaster_type_id,title,occurred_at,status,data_origin,updated_at,ended_at) values(?,?,?,?,?,'OFFICIAL',?,?)",
                disaster,type.get("disaster_type_id"),title,start,status,now,ended);
            db.update("""
                insert into source_disaster(source_disaster_id,disaster_id,source_id,external_id,source_record_key,source_address,source_url,
                    original_text,source_status,source_updated_at,collected_at,content_hash) values(?,?,?,?,?,?,?,?,?,?,?,?)
                """,record,disaster,source,announcement,zone+":"+start,Objects.toString(row.get("areaName"),null),HEAVY_RAIN.endpoint.toString(),
                original,sourceStatus,issued,now,hash);
        }else{
            db.update("update disaster set title=?,status=?,ended_at=?,updated_at=? where disaster_id=?",title,status,ended,now,disaster);
            db.update("update source_disaster set original_text=?,content_hash=?,source_status=?,source_updated_at=?,collected_at=? where source_disaster_id=?",
                original,hash,sourceStatus,issued,now,record);
        }
        int version=(int)db.count("select count(*) from source_disaster_revision where source_disaster_id=?",record)+1;
        UUID revision=UUID.randomUUID();
        db.update("insert into source_disaster_revision(revision_id,source_disaster_id,revision_no,original_text,source_address,source_status,content_hash,observed_at) values(?,?,?,?,?,?,?,?)",
            revision,record,version,original,Objects.toString(row.get("areaName"),null),sourceStatus,hash,now);
        if(old==null)events.append(disaster,record,revision,"NEW",null,null,"NEW:"+disaster,now);
        events.append(disaster,record,revision,"REVISION",null,null,"REVISION:"+revision,now);
        db.update("insert into disaster_change(change_id,disaster_id,change_kind,new_value,changed_at) values(?,?,?,?::jsonb,?)",
            UUID.randomUUID(),disaster,lift?"STATUS_CHANGED":old==null?"NEW":"SOURCE_UPDATED",
            "{\"sourceRevision\":"+version+"}",now);
        return new Result(lift?"HEAVY_RAIN_LIFTED":"HEAVY_RAIN_ACTIVE",record);
    }
}

package com.disasterinfo.eodiisseo.disaster.service;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import com.disasterinfo.eodiisseo.disaster.collection.*;
import com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.*;

/** Consumes verified associations only. Never derives incident identity from time/place/text similarity. */
@Service @Profile("!test")
public class VerifiedIncidentService {
    private final OwnedDatabase db;private final DisasterService earthquakes;private final DisasterRuntimeEvents events;
    private final Optional<VerifiedIncidentLinkPort> links;
    public VerifiedIncidentService(OwnedDatabase db,DisasterService earthquakes,DisasterRuntimeEvents events,Optional<VerifiedIncidentLinkPort> links){
        this.db=db;this.earthquakes=earthquakes;this.events=events;this.links=links;
    }
    @Transactional
    public OfficialRecordNormalizer.Result normalize(UUID source,OfficialProviderClient.Provider provider,Map<String,Object> row,String original,Instant now){
        if(provider!=EARTHQUAKE && provider!=WILDFIRE_MESSAGES)throw new CollectionFailure("SOURCE_CONTRACT");
        if(db.count("select count(*) from data_source where source_id=? and active and source_kind='DISASTER' and endpoint_key=?",source,provider.endpointKey)!=1)throw new CollectionFailure("SOURCE_CONTRACT");
        String unlinked=provider==EARTHQUAKE?"EARTHQUAKE_UNLINKED":"WILDFIRE_MESSAGE_UNLINKED";
        String announcement;
        try{announcement=OfficialRecordNormalizer.identity(provider,row);}catch(IllegalArgumentException e){return new OfficialRecordNormalizer.Result("INVALID_FACTS",null);}
        String hash=AuthService.hash(original);
        if(links.isEmpty())return new OfficialRecordNormalizer.Result(unlinked,null);
        var link=links.get().resolve(source,provider,announcement,hash).orElse(null);
        if(link==null)return new OfficialRecordNormalizer.Result(unlinked,null);
        if(!valid(link,source,provider,announcement,hash,row,now))return new OfficialRecordNormalizer.Result("INVALID_INCIDENT_EVIDENCE",null);
        db.rows("select pg_advisory_xact_lock(hashtextextended(?,0))","incident:"+source+":"+link.incidentId());
        var head=db.one("select * from owned_incident_head where source_id=? and incident_key=? for update",source,link.incidentId());
        if(head!=null){
            long sequence=((Number)head.get("official_sequence")).longValue();
            if(link.officialSequence()<sequence||link.issuedAt().isBefore(OwnedDatabase.instant(head.get("issued_at"))))
                return new OfficialRecordNormalizer.Result("OUT_OF_ORDER_INCIDENT",null);
            if(link.officialSequence()==sequence){
                if(!hash.equals(head.get("content_sha256"))||!announcement.equals(head.get("announcement_key")))
                    return new OfficialRecordNormalizer.Result("INCIDENT_SEQUENCE_CONFLICT",null);
                return new OfficialRecordNormalizer.Result("INCIDENT_DUPLICATE",(UUID)head.get("source_disaster_id"));
            }
            if(hash.equals(head.get("content_sha256")))return new OfficialRecordNormalizer.Result("INCIDENT_SEQUENCE_CONFLICT",null);
        }
        String key="incident:"+link.incidentId();
        UUID record;
        if(provider==EARTHQUAKE){
            var saved=earthquakes.saveEarthquakeRevision(source,key,row,original,now);
            // An existing source row is not proof that this announcement created a revision.
            if(saved.isEmpty())return new OfficialRecordNormalizer.Result("INVALID_FACTS",null);
            record=saved.get().sourceRecordId();
            UUID disaster=saved.get().disasterId(),revisionId=saved.get().revisionId();
            // Only this validated association path may emit earthquake events; a key prefix is not evidence.
            if(head==null)events.append(disaster,record,revisionId,"NEW",null,null,"NEW:"+disaster,now);
            events.append(disaster,record,revisionId,"REVISION",null,null,"REVISION:"+revisionId,now);
            db.update("update source_disaster set external_id=?,source_updated_at=?,source_url=? where source_disaster_id=?",announcement,link.issuedAt(),provider.endpoint.toString(),record);
        }else record=fire(source,key,announcement,row,original,link,head,now);
        db.update("""
            insert into owned_incident_head(source_id,incident_key,source_disaster_id,official_sequence,issued_at,
                announcement_key,content_sha256,evidence_url,contract_version) values(?,?,?,?,?,?,?,?,?)
            on conflict(source_id,incident_key) do update set official_sequence=excluded.official_sequence,issued_at=excluded.issued_at,
                announcement_key=excluded.announcement_key,content_sha256=excluded.content_sha256,evidence_url=excluded.evidence_url,contract_version=excluded.contract_version
            """,source,link.incidentId(),record,link.officialSequence(),link.issuedAt(),announcement,hash,link.evidenceUrl().toString(),link.contractVersion());
        return new OfficialRecordNormalizer.Result(provider==EARTHQUAKE?"EARTHQUAKE_LINKED":"WILDFIRE_LINKED",record);
    }
    private boolean valid(VerifiedIncidentLinkPort.Link link,UUID source,OfficialProviderClient.Provider provider,String announcement,String hash,Map<String,Object> row,Instant now){
        if(!source.equals(link.source())||provider!=link.provider()||!announcement.equals(link.announcement())||!hash.equals(link.contentSha256())
            ||link.incidentId()==null||!link.incidentId().matches("[A-Za-z0-9._:-]{1,180}")||link.officialSequence()<1
            ||link.issuedAt()==null||link.occurredAt()==null||link.issuedAt().isAfter(now)||link.occurredAt().isAfter(link.issuedAt())
            ||link.state()==null||link.contractVersion()==null||link.contractVersion().isBlank()||link.contractVersion().length()>200
            ||!officialEvidence(link.evidenceUrl()))return false;
        if(link.state()==VerifiedIncidentLinkPort.State.ENDED){
            if(link.endedAt()==null||link.endedAt().isBefore(link.occurredAt())||link.endedAt().isAfter(link.issuedAt()))return false;
        }else if(link.endedAt()!=null)return false;
        try{
            if(provider==EARTHQUAKE)return link.state()==VerifiedIncidentLinkPort.State.OCCURRED
                &&link.issuedAt().equals(OfficialRecordNormalizer.time(row.get("tmFc")))
                &&link.occurredAt().equals(OfficialRecordNormalizer.time(row.get("tmEqk")));
            if(provider!=WILDFIRE_MESSAGES||!"산불".equals(row.get("DST_SE_NM")))return false;
            Instant sent=LocalDateTime.parse(Objects.toString(row.get("CRT_DT"),""),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                .withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.of("Asia/Seoul")).toInstant();
            return sent.equals(link.issuedAt());
        }catch(RuntimeException invalid){return false;}
    }
    public static boolean officialEvidence(java.net.URI uri){
        if(uri==null||!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getFragment()!=null)return false;
        return Set.of("www.weather.go.kr","www.kma.go.kr","apihub.kma.go.kr","www.mois.go.kr","www.safetydata.go.kr","www.data.go.kr").contains(uri.getHost());
    }
    private UUID fire(UUID source,String key,String announcement,Map<String,Object> row,String original,VerifiedIncidentLinkPort.Link link,Map<String,Object> head,Instant now){
        var old=head==null?null:db.one("select * from source_disaster where source_disaster_id=? for update",head.get("source_disaster_id"));
        UUID disaster=old==null?UUID.randomUUID():(UUID)old.get("disaster_id"),record=old==null?UUID.randomUUID():(UUID)old.get("source_disaster_id");
        String hash=AuthService.hash(original),status=link.state().name();
        if(old==null){
            var type=db.one("select disaster_type_id from disaster_type where code='WILDFIRE' and active");
            if(type==null)throw new CollectionFailure("CATALOG_MISSING");
            db.update("insert into disaster(disaster_id,disaster_type_id,title,occurred_at,status,data_origin,updated_at,ended_at) values(?,?,?,? ,?,'OFFICIAL',?,?)",
                disaster,type.get("disaster_type_id"),"공식 사건 연결 산불",link.occurredAt(),status,now,link.endedAt());
            db.update("insert into source_disaster(source_disaster_id,disaster_id,source_id,external_id,source_record_key,source_url,original_text,source_status,source_updated_at,collected_at,content_hash) values(?,?,?,?,?,?,?,?,?,?,?)",
                record,disaster,source,announcement,key,WILDFIRE_MESSAGES.endpoint.toString(),original,status,link.issuedAt(),now,hash);
        }else{
            db.update("update disaster set occurred_at=?,status=?,ended_at=?,updated_at=? where disaster_id=?",link.occurredAt(),status,link.endedAt(),now,disaster);
            db.update("update source_disaster set external_id=?,original_text=?,source_status=?,source_updated_at=?,collected_at=?,content_hash=? where source_disaster_id=?",
                announcement,original,status,link.issuedAt(),now,hash,record);
        }
        int version=(int)db.count("select count(*) from source_disaster_revision where source_disaster_id=?",record)+1;
        UUID revision=UUID.randomUUID();
        db.update("insert into source_disaster_revision(revision_id,source_disaster_id,revision_no,original_text,source_status,content_hash,observed_at) values(?,?,?,?,?,?,?)",
            revision,record,version,original,status,hash,now);
        if(old==null)events.append(disaster,record,revision,"NEW",null,null,"NEW:"+disaster,now);
        events.append(disaster,record,revision,"REVISION",null,null,"REVISION:"+revision,now);
        db.update("insert into disaster_change(change_id,disaster_id,change_kind,new_value,changed_at) values(?,?,?,?::jsonb,?)",
            UUID.randomUUID(),disaster,old==null?"NEW":"SOURCE_UPDATED","{\"sourceRevision\":"+version+"}",now);
        return record;
    }
}

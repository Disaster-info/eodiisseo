package com.disasterinfo.eodiisseo.disaster.adapter;

import com.disasterinfo.eodiisseo.area.adapter.AreaNotificationInterestAreas;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.dashboard.DashboardQuery.Criteria;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.*;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader;
import com.disasterinfo.eodiisseo.group.repository.GroupRepository;
import com.disasterinfo.eodiisseo.notification.NotificationContracts;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Read-only domain adapters; unknown impact/severity remains unknown. */
@Component @Profile("!test")
public class NormalizedDisasterReader implements DashboardDisasterReader,NotificationContracts.DisasterReader,NotificationContracts.SeverityReader {
    private final OwnedDatabase db;private final GroupRepository groups;private final AreaNotificationInterestAreas areas;private final Clock clock;private final long staleSeconds;
    public NormalizedDisasterReader(OwnedDatabase db,GroupRepository groups,AreaNotificationInterestAreas areas,Clock clock,@Value("${LOCATION_STALE_AFTER_SECONDS:300}") long staleSeconds){this.db=db;this.groups=groups;this.areas=areas;this.clock=clock;this.staleSeconds=staleSeconds;}
    private String projection(){return "select d.*,t.code as type_code,v.code as severity_code,(select case when count(*)=1 then min(s.source_address) else null end from source_disaster s where s.disaster_id=d.disaster_id) as region from disaster d join disaster_type t using(disaster_type_id) left join severity_level v on v.severity_id=d.official_severity_id ";}
    public List<Candidate> findCandidates(UUID member,Criteria criteria,BaseLocation base){
        var interests=areas.findOwned(member);
        Double latitude=criteria.latitude(),longitude=criteria.longitude();
        if(latitude==null){
            var location=db.one("select ST_Y(l.point::geometry) latitude,ST_X(l.point::geometry) longitude from member_location l join member_location_consent c using(member_id) join member m using(member_id) where l.member_id=? and c.consented and m.status='ACTIVE' and l.updated_at>?",member,clock.instant().minusSeconds(staleSeconds));
            if(location!=null){latitude=((Number)location.get("latitude")).doubleValue();longitude=((Number)location.get("longitude")).doubleValue();}
        }
        var result=new ArrayList<Candidate>();
        for(var row:db.rows(projection()+"where d.data_origin='OFFICIAL' and d.status in ('OCCURRED','ONGOING') order by d.occurred_at desc,d.disaster_id")){
            UUID id=(UUID)row.get("disaster_id");Relevance relevance=Relevance.UNKNOWN;
            boolean related=false;
            for(var area:interests)if(areas.isRelated(id,area.id())){related=true;break;}
            if(related)relevance=Relevance.RELATED;
            else if(latitude!=null){
                var coverage=groups.findImpactCoverage(longitude,latitude,id);
                if(coverage.stream().anyMatch(r->Boolean.TRUE.equals(r.get("included"))))relevance=Relevance.RELATED;
                else if(!coverage.isEmpty()&&coverage.stream().allMatch(r->Boolean.TRUE.equals(r.get("known"))))relevance=Relevance.UNRELATED;
            }
            result.add(new Candidate(new MajorDisaster(id,(String)row.get("type_code"),(String)row.get("title"),(String)row.get("region"),DisasterStatus.valueOf((String)row.get("status")),(String)row.get("severity_code"),OwnedDatabase.instant(row.get("occurred_at")),OwnedDatabase.instant(row.get("updated_at"))),DataOrigin.OFFICIAL,relevance));
        }
        return List.copyOf(result);
    }
    public NotificationContracts.OfficialDisaster findOfficial(UUID id){
        var row=db.one(projection()+"where d.disaster_id=? and d.data_origin='OFFICIAL'",id);if(row==null)return null;
        var sources=db.rows("select original_text from source_disaster where disaster_id=?",id);
        // Representative content for multiple providers needs an explicit selection policy.
        if(sources.size()!=1)return null;
        return new NotificationContracts.OfficialDisaster(id,(String)row.get("type_code"),(String)row.get("region"),(String)row.get("title"),(String)sources.getFirst().get("original_text"),findById((UUID)row.get("official_severity_id")),NotificationContracts.DisasterStatus.valueOf((String)row.get("status")),true);
    }
    public NotificationContracts.Severity findByCode(String code){return severity(db.one("select * from severity_level where code=?",code));}
    public NotificationContracts.Severity findById(UUID id){return id==null?null:severity(db.one("select * from severity_level where severity_id=?",id));}
    private NotificationContracts.Severity severity(Map<String,Object> row){return row==null?null:new NotificationContracts.Severity((UUID)row.get("severity_id"),(String)row.get("code"),((Number)row.get("rank")).intValue());}
}

package com.disasterinfo.eodiisseo.disaster.adapter;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.*;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterDetailResponse;
import com.disasterinfo.eodiisseo.dashboard.dto.DisasterListQuery.Criteria;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterQueryReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.DataOrigin;
import java.time.Clock;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/** Official records only. Never combine provider originals or infer impact/severity. */
@Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class JdbcDisasterQueryReader implements DashboardDisasterQueryReader {
    private final OwnedDatabase db;
    private final Clock clock;
    public JdbcDisasterQueryReader(OwnedDatabase db,Clock clock){this.db=db;this.clock=clock;}
    private static final String SELECT="""
        select d.*,t.code type_code,v.code severity_code,
          (select case when count(*)=1 then min(s.source_address) end
             from source_disaster s where s.disaster_id=d.disaster_id) region
        from disaster d join disaster_type t using(disaster_type_id)
        left join severity_level v on v.severity_id=d.official_severity_id
        """;
    public boolean existsType(String code){return db.count("select count(*) from disaster_type where code=?",code)>0;}
    public ListSnapshot findDisasters(Criteria criteria){
        var sql=new StringBuilder(SELECT).append(" where d.data_origin='OFFICIAL'");
        var args=new ArrayList<Object>();
        if(criteria.type()!=null){sql.append(" and t.code=?");args.add(criteria.type());}
        if(criteria.status()!=null){sql.append(" and d.status=?");args.add(criteria.status().name());}
        else sql.append(" and d.status in ('OCCURRED','ONGOING')");
        sql.append(" order by d.occurred_at desc,d.disaster_id");
        // Lists have no per-item freshness field. Refuse a stale list instead of claiming it is latest.
        var rows=db.rows(sql.toString(),args.toArray());
        boolean fresh=rows.isEmpty()?earthquakeCollectionReady():rows.stream().allMatch(r->fresh((UUID)r.get("disaster_id")));
        return new ListSnapshot(rows.stream().map(r->new Entry(major(r),DataOrigin.OFFICIAL)).toList(),true,fresh);
    }
    private boolean earthquakeCollectionReady(){
        // Only the earthquake collector currently produces normalized disaster facts.
        var row=db.one("""
            select p.active,p.collection_interval_minutes,r.status latest_status,r.finished_at collected_at
            from data_source p left join lateral
              (select status,finished_at from collection_run where source_id=p.source_id and mode='LIVE'
               order by started_at desc,run_id desc limit 1) r on true
            where p.endpoint_key='KMA_EARTHQUAKE'
            """);
        return row!=null&&isFresh(row);
    }
    public Optional<DetailSnapshot> findDisaster(UUID id){
        var row=db.one(SELECT+" where d.disaster_id=? and d.data_origin='OFFICIAL'",id);
        if(row==null)return Optional.empty();
        var sources=db.rows("select s.*,p.name organization from source_disaster s join data_source p using(source_id) where s.disaster_id=? order by s.source_disaster_id",id);
        if(sources.size()!=1)throw new DashboardException(DashboardException.Reason.POLICY_UNRESOLVED,"A single official source is required; originals are never combined.");
        var source=sources.getFirst();var d=major(row);
        return Optional.of(new DetailSnapshot(new DisasterDetailResponse(id,d.type(),d.title(),d.region(),d.occurredAt(),d.severity(),d.status(),
            (String)source.get("original_text"),new DisasterDetailResponse.Source((String)source.get("organization")),d.updatedAt(),fresh(id)?"LATEST":"STALE"),DataOrigin.OFFICIAL));
    }
    private boolean fresh(UUID id){
        var sources=db.rows("""
            select p.active,p.collection_interval_minutes,
              (select r.status from collection_run r where r.source_id=s.source_id and r.mode='LIVE'
                order by r.started_at desc,r.run_id desc limit 1) latest_status,
              (select r.finished_at from collection_run r where r.source_id=s.source_id and r.mode='LIVE'
                order by r.started_at desc,r.run_id desc limit 1) collected_at
            from source_disaster s join data_source p using(source_id) where s.disaster_id=?
            """,id);
        return !sources.isEmpty()&&sources.stream().allMatch(this::isFresh);
    }
    private boolean isFresh(Map<String,Object> s){
            if(!Boolean.TRUE.equals(s.get("active"))||!"SUCCESS".equals(s.get("latest_status")))return false;
            var interval=(Number)s.get("collection_interval_minutes");
            var collected=OwnedDatabase.instant(s.get("collected_at"));
            return interval!=null&&interval.longValue()>0&&collected!=null&&!collected.isAfter(clock.instant())
                &&collected.isAfter(clock.instant().minusSeconds(interval.longValue()*60));
    }
    private MajorDisaster major(Map<String,Object> r){return new MajorDisaster((UUID)r.get("disaster_id"),(String)r.get("type_code"),(String)r.get("title"),(String)r.get("region"),DisasterStatus.valueOf((String)r.get("status")),(String)r.get("severity_code"),OwnedDatabase.instant(r.get("occurred_at")),OwnedDatabase.instant(r.get("updated_at")));}
}

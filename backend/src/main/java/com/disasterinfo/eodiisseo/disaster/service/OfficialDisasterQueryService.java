package com.disasterinfo.eodiisseo.disaster.service;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

/** Disaster-owned read model. Provider originals are selected, never concatenated. */
@Service @Profile("!test") @Transactional(readOnly=true)
public class OfficialDisasterQueryService {
    private final OwnedDatabase db;
    private final Clock clock;
    private final Duration freshness;
    public OfficialDisasterQueryService(OwnedDatabase db, Clock clock,
            @Value("${eodiisseo.disaster.source-freshness:PT24H}") Duration freshness) {
        if (freshness.isNegative() || freshness.isZero()) throw new IllegalArgumentException("Invalid freshness policy");
        this.db=db; this.clock=clock; this.freshness=freshness;
    }
    public List<Map<String,Object>> activeNationwide() {
        return db.rows(projection()+" where d.data_origin='OFFICIAL' and d.status in ('OCCURRED','ONGOING') order by d.occurred_at desc,d.disaster_id desc");
    }
    public Optional<Map<String,Object>> severityById(UUID id) {
        return id==null?Optional.empty():Optional.ofNullable(db.one("select * from severity_level where severity_id=?",id));
    }
    public Optional<Map<String,Object>> severityByCode(String code) {
        return code==null?Optional.empty():Optional.ofNullable(db.one("select * from severity_level where code=?",code));
    }
    public boolean existsType(String code) {
        return db.count("select count(*) from disaster_type where code=?",code)==1;
    }
    private String projection() {
        return """
            select d.*,t.code type_code,v.code severity_code,v.rank severity_rank
            from disaster d join disaster_type t using(disaster_type_id)
            left join severity_level v on v.severity_id=d.official_severity_id
            """;
    }
    public List<Map<String,Object>> list(String type,String status,int page,int size) {
        if(page<0 || size<1 || size>100) throw new IllegalArgumentException("Invalid page");
        var args=new ArrayList<Object>();
        String sql=projection()+" where d.data_origin='OFFICIAL' and (d.status not in ('ENDED','LIFTED') or d.ended_at>?)";
        args.add(clock.instant().minus(Duration.ofDays(90)));
        if(type!=null){sql+=" and t.code=?";args.add(type);}
        if(status!=null){sql+=" and d.status=?";args.add(status);}
        sql+=" order by d.occurred_at desc,d.disaster_id desc limit ? offset ?";
        args.add(size);args.add((long)page*size);
        return db.rows(sql,args.toArray());
    }
    public Optional<Map<String,Object>> find(UUID id) {
        return Optional.ofNullable(db.one(projection()+" where d.disaster_id=? and d.data_origin='OFFICIAL' and (d.status not in ('ENDED','LIFTED') or d.ended_at>?)",
                id,clock.instant().minus(Duration.ofDays(90))));
    }
    public String region(UUID id) {
        var regions=db.rows("""
            select a.name from disaster_area x join admin_area a using(area_code)
            where x.disaster_id=? and x.area_role='IMPACT' and a.active
            and x.basis=case when exists(select 1 from disaster_area o where o.disaster_id=x.disaster_id
                and o.area_role='IMPACT' and o.basis='OFFICIAL') then 'OFFICIAL' else 'SOURCE_ADDRESS' end
            order by a.area_code
            """,id);
        return regions.isEmpty()?null:String.join(", ",regions.stream().map(r->(String)r.get("name")).distinct().toList());
    }
    public Optional<Map<String,Object>> representative(UUID disaster) {
        // The current text/hash must match its latest immutable provider revision.
        return db.rows("""
            select s.*,r.revision_id,r.revision_no,r.observed_at,r.original_text revision_text,
                   p.name organization,p.collection_interval_minutes,p.active source_active,
                   coalesce(priority.priority,2147483647) provider_priority
            from source_disaster s join data_source p using(source_id)
            join lateral(select * from source_disaster_revision x where x.source_disaster_id=s.source_disaster_id
                order by revision_no desc limit 1) r on r.content_hash=s.content_hash and r.original_text=s.original_text
            left join disaster_source_priority priority on priority.source_id=s.source_id
            join disaster d using(disaster_id)
            where s.disaster_id=? and d.data_origin='OFFICIAL'
            and length(trim(r.original_text))>0 and r.observed_at<=? and s.collected_at<=?
            and (s.source_updated_at is null or s.source_updated_at<=?)
            order by provider_priority,coalesce(s.source_updated_at,r.observed_at) desc,s.source_disaster_id desc
            """,disaster,clock.instant(),clock.instant(),clock.instant()).stream().findFirst();
    }
    public Optional<Map<String,Object>> revision(UUID id) {
        return Optional.ofNullable(db.one("""
            select r.*,s.disaster_id,s.source_id,d.status,d.ended_at,t.code type_code,
                   v.code severity_code,d.data_origin
            from source_disaster_revision r join source_disaster s using(source_disaster_id)
            join disaster d using(disaster_id) join disaster_type t using(disaster_type_id)
            left join severity_level v on v.severity_id=d.official_severity_id
            where r.revision_id=? and d.data_origin='OFFICIAL' and r.observed_at<=?
            and (d.status not in ('ENDED','LIFTED') or d.ended_at>?)
            """,id,clock.instant(),clock.instant().minus(Duration.ofDays(90))));
    }
    public boolean isCurrent(UUID revision) {
        return db.count("""
            select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id)
            join disaster d using(disaster_id) where r.revision_id=? and d.data_origin='OFFICIAL' and r.observed_at<=? and s.collected_at<=?
            and r.content_hash=s.content_hash and r.original_text=s.original_text
            and not exists(select 1 from source_disaster_revision n where n.source_disaster_id=r.source_disaster_id
                and n.revision_no>r.revision_no)
            and (d.status not in ('ENDED','LIFTED') or d.ended_at>?)
            """,revision,clock.instant(),clock.instant(),clock.instant().minus(Duration.ofDays(90)))==1;
    }
    public List<Map<String,Object>> ended(Instant cutoff) {
        return db.rows("select disaster_id,status,ended_at from disaster where data_origin='OFFICIAL' and status in ('ENDED','LIFTED') and ended_at<=? order by disaster_id",cutoff);
    }
    public boolean latest(Map<String,Object> source) {
        Instant collected=OwnedDatabase.instant(source.get("collected_at"));
        if(!Boolean.TRUE.equals(source.get("source_active")) || collected==null || collected.isAfter(clock.instant())
                || collected.isBefore(clock.instant().minus(source.get("collection_interval_minutes") instanceof Number minutes && minutes.longValue()>0
                    ? Duration.ofMinutes(minutes.longValue()).compareTo(freshness)<0?Duration.ofMinutes(minutes.longValue()):freshness : freshness)))return false;
        var last=db.one("select status from collection_run where source_id=? and mode='LIVE' order by started_at desc,run_id desc limit 1",source.get("source_id"));
        return last!=null && "SUCCESS".equals(last.get("status"));
    }
    public void requireLatest(Map<String,Object> source) {
        // No unapproved extra dataStatus values are added to the existing HTTP DTO.
        if(!latest(source))throw new DashboardException(DashboardException.Reason.DEPENDENCY_UNAVAILABLE,"Official collection freshness is unavailable.");
    }
}

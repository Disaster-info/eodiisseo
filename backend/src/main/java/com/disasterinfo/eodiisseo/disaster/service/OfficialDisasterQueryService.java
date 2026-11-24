package com.disasterinfo.eodiisseo.disaster.service;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader;
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

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public VerifiedList verifiedList(String type, String status, int page, int size) {
        var rows = list(type, status, page, size);
        if (rows.isEmpty()) {
            requireVerifiedEmptyList(type);
            return new VerifiedList(rows, "LATEST");
        }
        return new VerifiedList(rows, haveCurrentListCoverage(rows)
                ? ("EARTHQUAKE".equals(type) ? "LATEST" : "PARTIAL_COVERAGE")
                : "STALE_OR_UNVERIFIED");
    }

    public record VerifiedList(List<Map<String,Object>> rows, String dataStatus) {}

    public boolean hasCurrentCoverage(UUID disasterId) {
        return haveCurrentListCoverage(List.of(Map.of("disaster_id", disasterId)));
    }

    private boolean haveCurrentListCoverage(List<Map<String,Object>> rows) {
        var ids = new StringJoiner(",");
        rows.forEach(row -> ids.add(row.get("disaster_id").toString()));
        Instant now = clock.instant();
        var today = java.sql.Date.valueOf(LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul")));
        // A single batch validates each listed incident and every linked provider. Unsupported
        // sources remain visible as historical facts without asserting current coverage.
        return db.count("""
                select count(*) from unnest(string_to_array(?, ',')::uuid[]) ids(id)
                where exists(select 1 from source_disaster s where s.disaster_id=ids.id)
                  and not exists (
                    select 1 from source_disaster s join data_source p using(source_id)
                    left join lateral (
                      select run_id,status,finished_at from collection_run r
                      where r.source_id=p.source_id and r.mode='LIVE'
                      order by started_at desc,run_id desc limit 1
                    ) r on true
                    where s.disaster_id=ids.id and not coalesce(
                      p.active and p.endpoint_key='KMA_EARTHQUAKE' and r.status='SUCCESS'
                      and r.finished_at between ? and ?
                      and (p.collection_interval_minutes is null or
                          r.finished_at >= cast(? as timestamptz)-p.collection_interval_minutes*interval '1 minute')
                      and s.collected_at <= ?
                      and exists(select 1 from owned_collection_coverage c join owned_collection_cursor o using(source_id)
                        where c.run_id=r.run_id and c.source_id=p.source_id
                          and c.from_day<=? and c.to_day>=? and o.completed_through>=?)
                      and exists(select 1 from lateral (
                        select content_hash,original_text,observed_at from source_disaster_revision r2
                        where r2.source_disaster_id=s.source_disaster_id order by revision_no desc limit 1
                      ) latest where latest.content_hash=s.content_hash and latest.original_text=s.original_text
                        and latest.observed_at<=?), false)
                  )
                """, ids.toString(), now.minus(freshness), now, now, now, today, today, today, now) == rows.size();
    }

    /** A recent run alone cannot establish that the requested scope was searched. */
    private void requireVerifiedEmptyList(String type) {
        String sourceKey = "EARTHQUAKE".equals(type) ? "KMA_EARTHQUAKE" : null;
        if (type != null && sourceKey == null)
            throw new DashboardException(DashboardException.Reason.DISASTER_COVERAGE_UNVERIFIED, "Provider coverage is unverified.");
        var run = db.one("""
                select p.source_id, p.active source_active, p.collection_interval_minutes,
                       r.run_id, r.status, r.finished_at collected_at
                from collection_run r join data_source p using(source_id)
                where r.mode='LIVE' and p.source_kind='DISASTER' and (cast(? as text) is null or p.endpoint_key=?)
                order by r.started_at desc,r.run_id desc limit 1
                """, sourceKey, sourceKey);
        if (run == null)
            throw new DashboardException(DashboardException.Reason.DISASTER_COLLECTION_NOT_STARTED, "Official collection has not run.");
        if ("FAILED".equals(run.get("status")))
            throw new DashboardException(DashboardException.Reason.DISASTER_COLLECTION_FAILED, "Official collection failed.");
        if (sourceKey != null && "SUCCESS".equals(run.get("status")) && latest(run)
                && db.tableExists("owned_collection_coverage") && db.tableExists("owned_collection_cursor")) {
            var today = java.sql.Date.valueOf(LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul")));
            if (db.count("""
                    select count(*) from owned_collection_coverage c join owned_collection_cursor p using(source_id)
                    where c.run_id=? and c.source_id=? and c.from_day<=? and c.to_day>=? and p.completed_through>=?
                    """, run.get("run_id"), run.get("source_id"), today, today, today) == 1) return;
        }
        throw new DashboardException(DashboardException.Reason.DISASTER_COVERAGE_UNVERIFIED, "Provider coverage is unverified.");
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DisasterImpactAreaReader impacts;
    public String region(UUID id) { return region(id,false); }
    public Map<UUID,String> displayRegions(List<Map<String,Object>> disasters) {
        var areas=new LinkedHashMap<UUID,Set<String>>();
        var ordinary=new StringJoiner(",");
        var rain=new ArrayList<UUID>();
        for(var disaster:disasters) {
            UUID id=(UUID)disaster.get("disaster_id");
            if("HEAVY_RAIN".equals(disaster.get("type_code")))rain.add(id);
            else ordinary.add(id.toString());
        }
        if(ordinary.length()>0) {
            for(var row:areaRows("x.disaster_id=any(string_to_array(?, ',')::uuid[])",ordinary.toString(),false)) {
                areas.computeIfAbsent((UUID)row.get("disaster_id"),id->new LinkedHashSet<>()).add((String)row.get("name"));
            }
        }
        var regions=new HashMap<UUID,String>();
        areas.forEach((id,names)->regions.put(id,String.join(", ",names)));
        for(UUID id:rain) {
            String region=rainRegion(id,false);
            if(region!=null)regions.put(id,region);
        }
        return regions;
    }
    /** Notification eligibility requires official impact evidence, never a source address. */
    public String notificationRegion(UUID id) { return region(id,true); }
    private String region(UUID id,boolean officialImpactOnly) {
        var type=db.one("select t.code from disaster d join disaster_type t using(disaster_type_id) where d.disaster_id=?",id);
        if(type!=null && "HEAVY_RAIN".equals(type.get("code"))) {
            return rainRegion(id,officialImpactOnly);
        }
        var regions=areaRows("x.disaster_id=?",id,officialImpactOnly);
        return regions.isEmpty()?null:String.join(", ",regions.stream().map(r->(String)r.get("name")).distinct().toList());
    }
    private String rainRegion(UUID id,boolean officialImpactOnly) {
        var impact=impacts==null?DisasterImpactAreaReader.Snapshot.unknown():impacts.find(id,clock.instant());
        if(impact.knowledge()!=DisasterImpactAreaReader.Knowledge.VERIFIED)return null;
        if(officialImpactOnly){
            Set<String> materialized=new TreeSet<>();
            db.rows("select x.area_code from disaster_area x join admin_area a using(area_code) where x.disaster_id=? and x.area_role='IMPACT' and x.basis='OFFICIAL' and a.active and a.level='SIGUNGU'",id)
                .forEach(r->materialized.add((String)r.get("area_code")));
            // Read-only safety gate: revoked/changed mappings cannot authorize stale area-owner matches on retry.
            if(!materialized.equals(impact.exactAdminAreaCodes()))return null;
        }
        var codes=impact.exactAdminAreaCodes().stream().sorted().toList();
        var names=new LinkedHashSet<String>();
        for(String code:codes) {
            var area=db.one("select name from admin_area where area_code=? and active",code);
            if(area==null)return null;
            names.add((String)area.get("name"));
        }
        return String.join(", ",names);
    }
    private List<Map<String,Object>> areaRows(String idPredicate,Object ids,boolean officialImpactOnly) {
        return db.rows("""
            select x.disaster_id,a.name from disaster_area x join admin_area a using(area_code)
            where %s and x.area_role='IMPACT' and a.active
            and (? or x.basis='OFFICIAL')
            and x.basis=case when exists(select 1 from disaster_area o where o.disaster_id=x.disaster_id
                and o.area_role='IMPACT' and o.basis='OFFICIAL') then 'OFFICIAL' else 'SOURCE_ADDRESS' end
            order by x.disaster_id,a.area_code
            """.formatted(idPredicate),ids,!officialImpactOnly);
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
        var last=db.one("select run_id,status from collection_run where source_id=? and mode='LIVE' order by started_at desc,run_id desc limit 1",source.get("source_id"));
        if(last!=null && db.tableExists("owned_collection_page") && db.count("select count(*) from owned_collection_page where run_id=?",last.get("run_id"))>0){
            // A completed historical/snapshot fetch is not evidence of current, gap-free coverage.
            if(!db.tableExists("owned_collection_coverage"))return false;
            var today=java.sql.Date.valueOf(java.time.LocalDate.ofInstant(clock.instant(),java.time.ZoneId.of("Asia/Seoul")));
            if(db.count("select count(*) from owned_collection_coverage c join owned_collection_cursor p using(source_id) where c.run_id=? and c.to_day>=? and p.completed_through>=?",last.get("run_id"),today,today)!=1)return false;
        }
        return last!=null && "SUCCESS".equals(last.get("status"));
    }
    public void requireLatest(Map<String,Object> source) {
        // No unapproved extra dataStatus values are added to the existing HTTP DTO.
        if(!latest(source))throw new DashboardException(DashboardException.Reason.DEPENDENCY_UNAVAILABLE,"Official collection freshness is unavailable.");
    }
}

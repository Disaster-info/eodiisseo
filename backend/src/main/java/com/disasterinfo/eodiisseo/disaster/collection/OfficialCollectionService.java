package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Explicit internal tool and scheduler entry. Network I/O is outside DB transactions. No startup or dashboard polling. */
@Service @Profile("!test")
public class OfficialCollectionService {
    private final OwnedDatabase db;private final OfficialRecordNormalizer normalizer;private final TransactionTemplate tx;private final Clock clock;
    public OfficialCollectionService(OwnedDatabase db,OfficialRecordNormalizer normalizer,PlatformTransactionManager manager,Clock clock){
        this.db=db;this.normalizer=normalizer;this.tx=new TransactionTemplate(manager);this.clock=clock;
    }
    public record Outcome(String status,int received,int normalized){}
    public Outcome collect(UUID source,OfficialProviderClient client,String key,LocalDate from,LocalDate to,String owner){
        if(!"owned-disaster-development".equals(owner))throw new CollectionFailure("OWNER_CONFIRMATION_REQUIRED");
        if(source==null||from==null||to==null||to.isBefore(from)||to.isAfter(from.plusDays(2))
            ||to.isAfter(LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Seoul"))))throw new CollectionFailure("WINDOW_LIMIT");
        UUID run=UUID.randomUUID();Instant now=clock.instant();
        Boolean claimed=tx.execute(status->{
            if(db.count("select count(*) from data_source where source_id=? and active and source_kind='DISASTER' and endpoint_key=?",source,client.provider().endpointKey)!=1)
                throw new CollectionFailure("SOURCE_CONTRACT");
            db.update("insert into owned_collection_cursor(source_id) values(?) on conflict do nothing",source);
            var cursor=db.one("select * from owned_collection_cursor where source_id=? for update",source);
            if(OwnedDatabase.instant(cursor.get("next_attempt_at")).isAfter(now))return false;
            // Use the canonical lock protocol, including owner run/source/mode FK, to exclude another collector.
            var lock=db.one("select * from collection_job_lock where source_id=? and mode='LIVE' for update",source);
            if(lock!=null&&OwnedDatabase.instant(lock.get("lease_until")).isAfter(now))return false;
            if(lock!=null)db.update("update collection_run set status='FAILED',finished_at=?,error_code='LEASE_EXPIRED' where run_id=? and status='RUNNING'",now,lock.get("run_id"));
            db.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type) values(?,?,'LIVE',?,'RUNNING','SCHEDULED')",run,source,now);
            db.update("insert into collection_job_lock(source_id,mode,run_id,lease_until) values(?,'LIVE',?,?) on conflict(source_id,mode) do update set run_id=excluded.run_id,lease_until=excluded.lease_until",
                source,run,now.plus(Duration.ofMinutes(30)));
            db.update("update owned_collection_cursor set next_attempt_at=? where source_id=?",now.plusSeconds(60),source);
            return true;
        });
        if(!Boolean.TRUE.equals(claimed))return new Outcome("BUSY_OR_RATE_LIMITED",0,0);
        try{
            List<OfficialProviderClient.Page> pages=new ArrayList<>();int total=-1,received=0;
            do{
                tx.executeWithoutResult(status->{
                    LocalDate day=LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC);
                    var budget=db.one("select budget_day,requests_today from owned_collection_cursor where source_id=? for update",source);
                    int used=budget.get("budget_day") instanceof java.sql.Date date && date.toLocalDate().equals(day)?((Number)budget.get("requests_today")).intValue():0;
                    var live=db.one("select run_id,lease_until from collection_job_lock where source_id=? and mode='LIVE' for update",source);
                    if(live==null||!run.equals(live.get("run_id"))||!OwnedDatabase.instant(live.get("lease_until")).isAfter(clock.instant()))throw new CollectionFailure("LEASE_LOST");
                    if(used>=1000)throw new CollectionFailure("DAILY_BUDGET_EXHAUSTED");
                    db.update("update owned_collection_cursor set budget_day=?,requests_today=? where source_id=?",java.sql.Date.valueOf(day),used+1,source);
                });
                var page=client.page(key,from,to,pages.size()+1);
                if(total<0)total=page.total();else if(total!=page.total())throw new CollectionFailure("PAGING_CHANGED");
                pages.add(page);received+=page.items().size();
                if(received>total||pages.size()>100)throw new CollectionFailure("PAGE_INCOMPLETE");
            }while(received<total);
            int count=received;
            return tx.execute(status->{
                db.one("select source_id from owned_collection_cursor where source_id=? for update",source);
                var lock=db.one("select run_id,lease_until from collection_job_lock where source_id=? and mode='LIVE' for update",source);
                Instant finished=clock.instant();
                if(lock==null||!run.equals(lock.get("run_id"))||!OwnedDatabase.instant(lock.get("lease_until")).isAfter(finished))throw new CollectionFailure("LEASE_LOST");
                int saved=0;
                List<Map<String,Object>> rows=new ArrayList<>();Set<String> pageIdentities=new HashSet<>();Set<String> messageIds=new HashSet<>();
                for(var page:pages){
                    db.update("insert into owned_collection_page(page_id,run_id,page_no,raw_body,body_sha256,fetched_at) values(?,?,?,?,?,?)",
                        UUID.randomUUID(),run,page.number(),page.raw(),java.util.HexFormat.of().formatHex(digest(page.raw())),finished);
                    rows.addAll(page.items());
                }
                rows.sort(Comparator.comparing(row->Objects.toString(row.get("tmFc"),"")));
                for(var row:rows){
                    if(client.provider()!=OfficialProviderClient.Provider.WILDFIRE_MESSAGES){
                        LocalDate issued;
                        try{issued=LocalDate.ofInstant(OfficialRecordNormalizer.time(row.get("tmFc")),ZoneId.of("Asia/Seoul"));}
                        catch(RuntimeException invalid){throw new CollectionFailure("ITEM_TIME_INVALID");}
                        if(issued.isBefore(from)||issued.isAfter(to))throw new CollectionFailure("WINDOW_RESPONSE_MISMATCH");
                    }
                    String original=OfficialProviderClient.canonical(row),hash=AuthService.hash(original),identity;
                    try{identity=OfficialRecordNormalizer.identity(client.provider(),row);}catch(IllegalArgumentException e){identity="INVALID:"+hash;}
                    // Even a different MDFCN_YMD for the same SN makes this finite message snapshot unstable.
                    if(client.provider()==OfficialProviderClient.Provider.WILDFIRE_MESSAGES && !messageIds.add(identity))throw new CollectionFailure("PAGING_CHANGED");
                    if(!pageIdentities.add(identity+":"+hash))throw new CollectionFailure("PAGING_CHANGED");
                    // An identical historical announcement is not replayed over a more recent state.
                    var previous=db.one("select source_disaster_id from owned_collection_item where source_id=? and provider_record_key=? and content_sha256=?",source,identity,hash);
                    if(previous!=null && previous.get("source_disaster_id")!=null){
                        db.update("update owned_collection_item set last_seen_at=? where source_id=? and provider_record_key=? and content_sha256=?",finished,source,identity,hash);
                        continue;
                    }
                    var result=normalizer.normalize(source,client.provider(),row,original,finished);
                    db.update("insert into owned_collection_item(item_id,source_id,provider_record_key,content_sha256,canonical_json,first_seen_at,last_seen_at,classification,source_disaster_id) values(?,?,?,?,?,?,?,?,?) on conflict(source_id,provider_record_key,content_sha256) do update set last_seen_at=excluded.last_seen_at,classification=excluded.classification,source_disaster_id=excluded.source_disaster_id",
                        UUID.randomUUID(),source,identity,hash,original,finished,finished,result.classification(),result.sourceRecord());
                    if(result.sourceRecord()!=null)saved++;
                }
                db.update("update collection_run set status='SUCCESS',finished_at=?,heartbeat_at=?,records_received=?,records_saved=? where run_id=?",finished,finished,count,saved,run);
                if(client.provider()!=OfficialProviderClient.Provider.WILDFIRE_MESSAGES){
                    db.update("insert into owned_collection_coverage(run_id,source_id,from_day,to_day) values(?,?,?,?)",run,source,java.sql.Date.valueOf(from),java.sql.Date.valueOf(to));
                    advanceCoverage(source,from);
                }
                db.update("update owned_collection_cursor set last_success_at=?,last_failure_code=null,next_attempt_at=? where source_id=?",
                    finished,finished.plusSeconds(60),source);
                db.update("delete from collection_job_lock where source_id=? and mode='LIVE' and run_id=?",source,run);
                // A successful fresh run confirms unchanged current records too.
                db.update("""
                    update source_disaster s set collected_at=? where s.source_id=? and exists(
                        select 1 from owned_collection_item i where i.source_id=? and i.last_seen_at=?
                        and i.source_disaster_id=s.source_disaster_id and i.content_sha256=s.content_hash)
                    """,finished,source,source,finished);
                return new Outcome("SUCCESS",count,saved);
            });
        }catch(RuntimeException failure){
            String code=failure instanceof CollectionFailure safe?safe.code():"PERSISTENCE_OR_NORMALIZATION";
            tx.executeWithoutResult(status->{
                db.one("select source_id from owned_collection_cursor where source_id=? for update",source);
                var current=db.one("select run_id from collection_job_lock where source_id=? and mode='LIVE' for update",source);
                db.update("update collection_run set status='FAILED',finished_at=?,error_code=? where run_id=? and status='RUNNING'",clock.instant(),code,run);
                if(current!=null && run.equals(current.get("run_id"))) {
                    db.update("update owned_collection_cursor set last_failure_code=?,next_attempt_at=? where source_id=?",code,clock.instant().plusSeconds(300),source);
                    db.update("delete from collection_job_lock where source_id=? and mode='LIVE' and run_id=?",source,run);
                }
            });
            return new Outcome(code,0,0);
        }
    }
    private void advanceCoverage(UUID source,LocalDate from) {
        var cursor=db.one("select completed_through,coverage_start from owned_collection_cursor where source_id=? for update",source);
        LocalDate start=cursor.get("coverage_start") instanceof java.sql.Date value?value.toLocalDate():from;
        LocalDate completed=cursor.get("completed_through") instanceof java.sql.Date value?value.toLocalDate():start.minusDays(1);
        for(int i=0;i<10000;i++) {
            LocalDate next=completed.plusDays(1);
            var row=db.one("select max(to_day) as last_day from owned_collection_coverage where source_id=? and from_day<=? and to_day>=?",
                source,java.sql.Date.valueOf(next),java.sql.Date.valueOf(next));
            if(row==null||row.get("last_day")==null) {
                db.update("update owned_collection_cursor set coverage_start=?,completed_through=? where source_id=?",java.sql.Date.valueOf(start),java.sql.Date.valueOf(completed),source);
                return;
            }
            completed=((java.sql.Date)row.get("last_day")).toLocalDate();
        }
        throw new CollectionFailure("COVERAGE_LIMIT");
    }
    private static byte[] digest(byte[] body){try{return java.security.MessageDigest.getInstance("SHA-256").digest(body);}catch(Exception e){throw new IllegalStateException();}}
}

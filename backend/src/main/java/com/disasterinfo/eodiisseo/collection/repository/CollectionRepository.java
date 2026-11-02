package com.disasterinfo.eodiisseo.collection.repository;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository @Profile("!test")
public class CollectionRepository {
    private final OwnedDatabase db;
    public CollectionRepository(OwnedDatabase db){this.db=db;}
    public OwnedDatabase database(){return db;}
    public void gate(){db.rows("select pg_advisory_xact_lock(78161108)");}
    public List<Map<String,Object>> sources(String key,boolean shelter){return db.rows("select * from data_source where active and (?::text is null or endpoint_key=?) and (not ? or source_kind='SHELTER') order by endpoint_key",key,key,shelter);}
    public Map<String,Object> pending(){return db.one("select r.*,s.endpoint_key from collection_run r join data_source s using(source_id) where r.status='RUNNING' and r.heartbeat_at is null order by r.started_at,r.run_id limit 1 for update of r skip locked");}
    public void expire(Instant now){
        db.update("update collection_run r set status='FAILED',finished_at=?,error_code='COLLECTION_LEASE_EXPIRED',error_message='수집 실행이 중단되었습니다.' from collection_job_lock l where l.run_id=r.run_id and l.lease_until<=? and r.status='RUNNING'",now,now);
        db.update("delete from collection_job_lock where lease_until<=?",now);
    }
    public boolean locked(UUID source,String mode){return db.count("select count(*) from collection_job_lock where source_id=? and mode=?",source,mode)>0;}
    public void enqueue(UUID run,UUID source,String mode,UUID member,UUID batch,Instant now,String trigger){
        db.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type,requested_by,batch_id) values(?,?,?,?,'RUNNING',?,?,?)",run,source,mode,now,trigger,member,batch);
        db.update("insert into collection_job_lock values(?,?,?,?)",source,mode,run,now.plusSeconds(600));
    }
    public boolean heartbeat(UUID run,Instant now){
        int updated=db.update("update collection_job_lock set lease_until=? where run_id=? and lease_until>?",now.plusSeconds(600),run,now);
        if(updated!=1)return false;
        return db.update("update collection_run set heartbeat_at=? where run_id=? and status='RUNNING'",now,run)==1;
    }
    public void finish(UUID run,boolean success,int received,int saved,Instant now){
        db.update("update collection_run set status=?,finished_at=?,records_received=?,records_saved=?,error_code=?,error_message=? where run_id=? and status='RUNNING'",success?"SUCCESS":"FAILED",now,received,saved,success?null:"COLLECTION_FAILED",success?null:"수집에 실패했습니다. 제공처 설정과 응답 규격을 확인하세요.",run);
        db.update("delete from collection_job_lock where run_id=?",run);
    }
    public void finishBatches(Instant now){
        db.update("update collection_batch b set status=case when not exists(select 1 from collection_run r where r.batch_id=b.batch_id and r.status='FAILED') then 'SUCCESS' when exists(select 1 from collection_run r where r.batch_id=b.batch_id and r.status='SUCCESS') then 'PARTIAL_FAILED' else 'FAILED' end,finished_at=? where b.status='RUNNING' and not exists(select 1 from collection_run r where r.batch_id=b.batch_id and r.status='RUNNING')",now);
    }
}

package com.disasterinfo.eodiisseo.collection.service;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.collection.client.CollectionClient;
import com.disasterinfo.eodiisseo.collection.repository.CollectionRepository;
import com.disasterinfo.eodiisseo.ops.dto.OpsDto.Accepted;
import java.time.*;
import java.util.*;

@Service @Profile("!test")
public class CollectionService {
    private final CollectionRepository repository;private final CollectionClient client;private final CollectionRecordWriter writer;
    private final TransactionTemplate tx;private final Clock clock;private final boolean enabled;
    @Value("${COLLECTION_FAILURE_RETRY_SECONDS:60}") private long failureRetrySeconds=60;
    @Value("${COLLECTION_RATE_LIMIT_RETRY_SECONDS:3600}") private long rateLimitRetrySeconds=3600;
    public CollectionService(CollectionRepository repository,CollectionClient client,CollectionRecordWriter writer,
            PlatformTransactionManager manager,Clock clock,@Value("${COLLECTION_WORKER_ENABLED:false}") boolean enabled){
        this.repository=repository;this.client=client;this.writer=writer;this.tx=new TransactionTemplate(manager);this.clock=clock;this.enabled=enabled;
    }
    public Accepted request(UUID member,String source,String mode,boolean shelters){
        if(!enabled)throw new ApiProblem(503,"COLLECTION_WORKER_NOT_CONNECTED","수집 worker를 활성화하세요.");
        if(!"LIVE".equals(mode))throw ApiProblem.bad("실제 수집은 LIVE 모드만 지원합니다.");
        return tx.execute(status->{
            repository.gate();repository.expire(clock.instant());repository.finishBatches(clock.instant());
            if(member!=null)repository.database().lockMember(member);
            var sources=repository.sources(source,shelters);
            if(sources.isEmpty())throw ApiProblem.bad("수집 제공처를 확인하세요.");
            var configured=client.configuredSources();
            for(var row:sources){
                if(!configured.contains(row.get("endpoint_key")))throw new ApiProblem(503,"COLLECTION_SOURCE_NOT_CONFIGURED","제공처 키 또는 수집 구현을 확인하세요.");
                if(repository.locked((UUID)row.get("source_id"),mode))throw ApiProblem.conflict("COLLECTION_ALREADY_RUNNING");
            }
            UUID batch=source==null?UUID.randomUUID():null;var now=clock.instant();
            if(batch!=null)repository.database().update("insert into collection_batch values(?,?,?,'RUNNING',?,null)",batch,mode,member,now);
            UUID first=null;
            for(var row:sources){UUID run=UUID.randomUUID();if(first==null)first=run;repository.enqueue(run,(UUID)row.get("source_id"),mode,member,batch,now,member==null?"SCHEDULED":"MANUAL");}
            return new Accepted(batch==null?first:batch,batch==null?"RUN":"BATCH","ACCEPTED");
        });
    }
    public List<Map<String,Object>> sources(){
        var configured=client.configuredSources();return repository.sources(null,false).stream().map(row->{
            var result=new LinkedHashMap<String,Object>();result.put("source",row.get("endpoint_key"));result.put("name",row.get("name"));result.put("configured",configured.contains(row.get("endpoint_key")));
            result.put("workerEnabled",enabled);
            result.put("latestRun",repository.database().one("select run_id as \"runId\",status,started_at as \"startedAt\",finished_at as \"finishedAt\",records_received as \"recordsReceived\",records_saved as \"recordsSaved\",error_code as \"errorCode\" from collection_run where source_id=? and mode='LIVE' order by started_at desc,run_id desc limit 1",row.get("source_id")));
            result.put("lastSuccessAt",repository.database().one("select max(finished_at) as \"finishedAt\" from collection_run where source_id=? and mode='LIVE' and status='SUCCESS'",row.get("source_id")).get("finishedAt"));
            return (Map<String,Object>)result;
        }).toList();
    }
    public void workOne(){
        if(!enabled)return;
        var job=tx.execute(status->{repository.gate();repository.expire(clock.instant());repository.finishBatches(clock.instant());var row=repository.pending();if(row!=null&&!repository.heartbeat((UUID)row.get("run_id"),clock.instant()))throw new IllegalStateException("Lease lost");return row;});
        if(job==null)return;
        UUID run=(UUID)job.get("run_id"),source=(UUID)job.get("source_id");String endpoint=(String)job.get("endpoint_key");
        int received=0;
        try{
            var records=client.fetch(endpoint,()->tx.executeWithoutResult(status->{if(!repository.heartbeat(run,clock.instant()))throw new IllegalStateException("Lease lost");}));
            received=records.size();
            tx.executeWithoutResult(status->{
                repository.gate();if(!repository.heartbeat(run,clock.instant()))throw new IllegalStateException("Lease lost");
                int saved=writer.save(source,endpoint,records,clock.instant());repository.finish(run,true,records.size(),saved,clock.instant());repository.finishBatches(clock.instant());
            });
        }catch(RuntimeException failure){
            int count=received;
            tx.executeWithoutResult(status->{repository.gate();
                // An expired worker must never finish a successor's run or write late provider data.
                if(repository.heartbeat(run,clock.instant())){
                    repository.finish(run,false,count,0,clock.instant());
                    String code=failure instanceof ApiProblem p?p.code():failure instanceof org.springframework.dao.DataAccessException?"COLLECTION_PERSISTENCE_FAILED":failure instanceof IllegalArgumentException?"COLLECTION_INVALID_RECORD":"COLLECTION_FAILED";
                    repository.database().update("update collection_run set error_code=? where run_id=?",code,run);
                    repository.database().update("insert into service_error_log values(?,?,'COLLECTION',?,'외부 자료 수집에 실패했습니다.',?)",UUID.randomUUID(),run,code,clock.instant());
                }
                repository.finishBatches(clock.instant());
            });
        }
    }
    public void enqueueDue(){
        if(!enabled)return;
        for(var source:repository.sources(null,false)){
            String endpoint=(String)source.get("endpoint_key");Object interval=source.get("collection_interval_minutes");
            if(interval==null||!client.configuredSources().contains(endpoint))continue;
            var latest=repository.database().one("select status,coalesce(finished_at,started_at) retry_at,error_code from collection_run where source_id=? and mode='LIVE' order by started_at desc,run_id desc limit 1",source.get("source_id"));
            long delay=((Number)interval).longValue()*60;
            if(latest!=null){
                if("RUNNING".equals(latest.get("status")))continue;
                if("FAILED".equals(latest.get("status")))delay="COLLECTION_PROVIDER_RATE_LIMITED".equals(latest.get("error_code"))?Math.max(60,rateLimitRetrySeconds):Math.max(1,failureRetrySeconds);
                if(com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.instant(latest.get("retry_at")).isAfter(clock.instant().minusSeconds(delay)))continue;
            }
            try{request(null,endpoint,"LIVE",false);}catch(ApiProblem expected){if(expected.status()!=409)throw expected;}
        }
    }
}

package com.disasterinfo.eodiisseo.collection.execution;

import com.disasterinfo.eodiisseo.collection.ownership.*;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit test/development construction only. Same DataSource/transaction contract as ownership. */
public final class JdbcCollectionExecutionStore implements CollectionExecutionStore {
    private final JdbcTemplate jdbc;private final JdbcCollectionOwnershipStore ownership;private final UUID workerSession;
    public JdbcCollectionExecutionStore(JdbcTemplate jdbc,PlatformTransactionManager manager){
        this(jdbc,manager,null);
    }
    public JdbcCollectionExecutionStore(JdbcTemplate jdbc,PlatformTransactionManager manager,UUID workerSession){
        this.workerSession=workerSession;this.jdbc=Objects.requireNonNull(jdbc);this.ownership=new JdbcCollectionOwnershipStore(jdbc,manager,workerSession);
    }
    private <T>T current(Claim c,Function<CollectionOwnership.Transaction,T> action){
        return ownership.transaction(tx->{tx.lock(c.assignment(),c.mode());tx.assertCurrent(c,true);return action.apply(tx);});
    }
    private void execution(Claim c){
        if(jdbc.queryForList("select run_id from collection_http_execution where run_id=? and claim_id=? and fence_token=? for update",c.run(),c.claimId(),c.fence()).size()!=1)
            throw new IllegalStateException("Execution journal does not match claim");
    }
    public void start(Claim c,CollectionProviderPort.Request request,CollectionProviderPort.Evidence evidence){
        current(c,tx->{
            if(c.assignment().contract().equals("MOIS_MESSAGES")){
                // All currently approved aliases must refer to one logical message target.
                var targets=jdbc.queryForList("""
                    select distinct a.target_id from data_source s join collection_source_assignment a
                    on a.source_id=s.source_id and a.epoch=s.collection_assignment_epoch
                    where s.active and a.approved and a.contract_key='MOIS_MESSAGES'
                    """);
                if(targets.size()!=1||!c.assignment().target().equals(targets.getFirst().get("target_id")))throw new IllegalStateException("Message alias target conflict");
            }
            jdbc.update("""
                insert into collection_http_execution(run_id,target_id,mode,claim_id,fence_token,from_date,to_date,page_rows,
                  timeout_ms,range_verified,snapshot_verified,empty_verified,incremental_verified,completeness_reference,state)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'COLLECTING')
                """,c.run(),c.assignment().target(),c.mode(),c.claimId(),c.fence(),request.from(),request.to(),request.rows(),
                    request.timeout().toMillis(),evidence.boundedRange(),evidence.stableSnapshot(),evidence.emptyResult(),evidence.incremental(),evidence.reference());
            return null;
        });
    }
    public Optional<Attempt> reserve(Claim c,int page,int number){
        return current(c,tx->{
            execution(c);
            if(!"COLLECTING".equals(jdbc.queryForObject("select state from collection_http_execution where run_id=?",String.class,c.run())))throw new IllegalStateException("Execution not collecting");
            tx.assertNoUncertainRequest(c);
            int prior=jdbc.queryForObject("select count(*) from collection_http_attempt where run_id=? and page_no=?",Integer.class,c.run(),page);
            int valid=jdbc.queryForObject("select count(*) from collection_http_attempt where run_id=? and state='VALIDATED'",Integer.class,c.run());
            if(number!=prior+1||page!=valid+1)throw new IllegalStateException("Request sequence conflict");
            var windows=jdbc.queryForList("""
                select * from collection_quota_window where quota_scope_id=? and starts_at<=clock_timestamp()
                and ends_at>clock_timestamp() order by starts_at for update
                """,c.assignment().quotaScope());
            if(windows.isEmpty())return Optional.empty();
            if(windows.size()!=1)throw new IllegalStateException("Overlapping quota approval windows");
            var window=windows.getFirst();
            int reserved=jdbc.update("""
                update collection_quota_window set reserved=reserved+1,
                  next_request_at=clock_timestamp()+min_interval_ms*interval '1 millisecond'
                where quota_scope_id=? and starts_at=? and ends_at>clock_timestamp()
                  and reserved<request_limit and next_request_at<=clock_timestamp()
                """,c.assignment().quotaScope(),window.get("starts_at"));
            if(reserved!=1)return Optional.empty();
            var id=UUID.randomUUID();
            jdbc.update("""
                insert into collection_http_attempt(attempt_id,run_id,page_no,attempt_no,quota_scope_id,quota_starts_at,state)
                values(?,?,?,?,?,?,'IN_FLIGHT')
                """,id,c.run(),page,number,c.assignment().quotaScope(),window.get("starts_at"));
            return Optional.of(new Attempt(id,c,page,number));
        });
    }
    private <T>T journal(Attempt a,Function<Void,T> action){
        // No ownership lock here: late results can only be archived in quarantine.
        return ownership.transaction(tx->{
            if(workerSession!=null){
                jdbc.queryForObject("select set_config('eodiisseo.collection.run',?,true)",String.class,a.claim().run().toString());
                jdbc.queryForObject("select set_config('eodiisseo.collection.claim',?,true)",String.class,a.claim().claimId().toString());
                jdbc.queryForObject("select set_config('eodiisseo.collection.fence',?,true)",String.class,Long.toString(a.claim().fence()));
            }
            execution(a.claim());
            if(jdbc.queryForList("select attempt_id from collection_http_attempt where attempt_id=? and run_id=? and page_no=? and attempt_no=? for update",a.id(),a.claim().run(),a.page(),a.number()).size()!=1)
                throw new IllegalStateException("Attempt does not match execution");
            return action.apply(null);
        });
    }
    public void received(Attempt a,CollectionProviderPort.Reply reply,CollectionArchivePolicy.Body body,CollectionArchivePolicy policy){
        journal(a,ignored->{
            var existing=jdbc.queryForList("select body_sha256 from collection_http_archive where attempt_id=?",a.id());
            if(!existing.isEmpty()){
                if(!body.sha256().equals(existing.getFirst().get("body_sha256")))throw new IllegalStateException("Conflicting response for journal attempt");
                return null;
            }
            jdbc.update("insert into collection_http_archive values(?,?,?,?,'QUARANTINE','UNVERIFIED',clock_timestamp()+?*interval '1 millisecond',?)",
                a.id(),body.sha256(),body.bytes(),body.omitted(),policy.retention().toMillis(),policy.approval());
            jdbc.update("""
                update collection_http_attempt set http_status=?,finished_at=clock_timestamp(),
                  state=case when state='UNKNOWN' then state else 'RECEIVED' end
                where attempt_id=? and state in ('IN_FLIGHT','UNKNOWN')
                """,reply.status(),a.id());
            return null;
        });
    }
    public void unknown(Attempt a){
        journal(a,ignored->{
            int changed=jdbc.update("update collection_http_attempt set state='UNKNOWN' where attempt_id=? and state in ('IN_FLIGHT','RECEIVED','UNKNOWN')",a.id());
            if(changed==1)jdbc.update("update collection_http_execution set state='UNKNOWN',reason='REQUEST_UNCERTAIN' where run_id=?",a.claim().run());
            return null;
        });
    }
    public void decide(Attempt a,String decision){
        if(!Set.of("RETRYABLE","REJECTED").contains(decision))throw new IllegalArgumentException("Unknown page decision");
        journal(a,ignored->{
            if(jdbc.update("update collection_http_attempt set state=? where attempt_id=? and state='RECEIVED'",decision,a.id())!=1)throw new IllegalStateException("Attempt unresolved or already decided");
            return null;
        });
    }
    public void validated(Attempt a,int total,int itemCount){
        if(total<0||total>10000||itemCount<0||itemCount>100||itemCount>total)throw new IllegalArgumentException("Invalid page metadata");
        journal(a,ignored->{
            if(jdbc.update("update collection_http_attempt set state='VALIDATED',declared_total=?,item_count=? where attempt_id=? and state='RECEIVED' and http_status=200",total,itemCount,a.id())!=1)
                throw new IllegalStateException("Attempt cannot be validated");
            return null;
        });
    }
    public void beginDomain(Claim c){
        current(c,tx->{execution(c);
            if(jdbc.update("update collection_http_execution set projection_required=true,domain_phase='HTTP_PENDING' where run_id=? and state='COLLECTING' and projection_required is null",c.run())!=1)
                throw new IllegalStateException("Domain execution registration conflict");
            return null;});
    }
    public void domainResult(Claim c,String phase,int stored,String reason){
        if(!Set.of("DOMAIN_APPLIED","DOMAIN_REVIEW","DOMAIN_FAILED").contains(phase)||stored<0||!reason.matches("[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("Domain outcome required");
        current(c,tx->{execution(c);
            if(jdbc.update("update collection_http_execution set domain_phase=?,state=?,reason=? where run_id=? and projection_required",phase,phase.equals("DOMAIN_APPLIED")?"COMPLETE":"FAILED",reason,c.run())!=1)
                throw new IllegalStateException("Domain result conflict");
            if(!phase.equals("DOMAIN_APPLIED"))jdbc.update("update collection_http_archive b set classification='QUARANTINE',reason=? from collection_http_attempt a where a.attempt_id=b.attempt_id and a.run_id=?",reason,c.run());
            jdbc.update("update collection_run set records_saved=? where run_id=? and claim_id=? and fence_token=?",stored,c.run(),c.claimId(),c.fence());return null;});
    }
    public void terminal(Claim c,State state,String reason){
        if(!Set.of(State.COMPLETE,State.INCOMPLETE,State.FAILED,State.QUOTA_BLOCKED).contains(state)||!reason.matches("[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("Safe terminal state required");
        current(c,tx->{
            execution(c);tx.assertNoUncertainRequest(c);
            if(state==State.COMPLETE){
                var evidence=jdbc.queryForMap("select * from collection_http_execution where run_id=?",c.run());
                var summary=jdbc.queryForMap("""
                    select min(declared_total) lo,max(declared_total) hi,sum(item_count) received,
                      min(page_no) first_page,max(page_no) last_page,count(*) pages
                    from collection_http_attempt where run_id=? and state='VALIDATED'
                    """,c.run());
                if(!Boolean.TRUE.equals(evidence.get("range_verified"))||!Boolean.TRUE.equals(evidence.get("snapshot_verified"))
                    ||!Boolean.TRUE.equals(evidence.get("incremental_verified"))||summary.get("lo")==null
                    ||((Number)summary.get("lo")).longValue()!=((Number)summary.get("hi")).longValue()
                    ||((Number)summary.get("hi")).longValue()!=((Number)summary.get("received")).longValue()
                    ||((Number)summary.get("first_page")).intValue()!=1
                    ||((Number)summary.get("last_page")).longValue()!=((Number)summary.get("pages")).longValue()
                    ||(((Number)summary.get("hi")).intValue()==0&&!Boolean.TRUE.equals(evidence.get("empty_verified"))))
                    throw new IllegalStateException("Completeness evidence or page coverage missing");
                long shortMiddle=jdbc.queryForObject("""
                    select count(*) from collection_http_attempt where run_id=? and state='VALIDATED'
                      and page_no<? and item_count<>?
                    """,Long.class,c.run(),summary.get("last_page"),evidence.get("page_rows"));
                if(shortMiddle!=0)throw new IllegalStateException("Middle page missing");
                long invalid=jdbc.queryForObject("""
                    select count(*) from collection_http_attempt a left join collection_http_archive b using(attempt_id)
                    where a.run_id=? and (a.state not in ('VALIDATED','RETRYABLE')
                      or b.attempt_id is null or (a.state='VALIDATED' and (a.http_status<>200 or b.sensitive_omitted)))
                    """,Long.class,c.run());
                long valid=jdbc.queryForObject("select count(*) from collection_http_attempt where run_id=? and state='VALIDATED'",Long.class,c.run());
                if(invalid!=0||valid==0)throw new IllegalStateException("Incomplete archive cannot be promoted");
                jdbc.update("""
                    update collection_http_archive b set classification='NORMAL',reason='COMPLETE_RESPONSE'
                    from collection_http_attempt a where b.attempt_id=a.attempt_id and a.run_id=? and a.state='VALIDATED'
                    """,c.run());
            }
            long received=jdbc.queryForObject("select coalesce(sum(item_count),0) from collection_http_attempt where run_id=? and state='VALIDATED'",Long.class,c.run());
            // No projection occurs in stage 2: saved remains zero, while received counts verified page items.
            if(jdbc.update("update collection_run set records_received=?,records_saved=0 where run_id=? and claim_id=? and fence_token=? and status='RUNNING'",received,c.run(),c.claimId(),c.fence())!=1)
                throw new IllegalStateException("Execution metrics fencing lost");
            if(jdbc.update("update collection_http_execution set state=?,reason=? where run_id=? and state='COLLECTING'",state.name(),reason,c.run())!=1)
                throw new IllegalStateException("Execution terminal conflict");
            return null;
        });
    }
}
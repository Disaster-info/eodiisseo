package com.disasterinfo.eodiisseo.collection.ownership;

import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Not registered as a bean. Requires the separately approved ownership schema and a stopped legacy fleet. */
public final class JdbcCollectionOwnershipStore implements Store {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UUID workerSession;
    public JdbcCollectionOwnershipStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc,manager,null);
    }
    /** Explicit isolated cutover context; never inferred from an application flag. */
    public JdbcCollectionOwnershipStore(JdbcTemplate jdbc,PlatformTransactionManager manager,UUID workerSession) {
        this.workerSession=workerSession;this.jdbc=Objects.requireNonNull(jdbc); Objects.requireNonNull(jdbc.getDataSource(), "JDBC DataSource required");
        boolean jdbcManager=manager instanceof DataSourceTransactionManager m && m.getDataSource()==jdbc.getDataSource();
        boolean jpaManager=manager instanceof JpaTransactionManager m && m.getDataSource()==jdbc.getDataSource()
            && m.getEntityManagerFactory() instanceof EntityManagerFactoryInfo info && info.getDataSource()==jdbc.getDataSource();
        if(!jdbcManager&&!jpaManager)
            throw new IllegalArgumentException("An explicit transaction manager for the same JDBC DataSource is required");
        transactions=new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public <T>T transaction(Function<Transaction,T> action) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Ownership writes cannot join a read-only transaction");
        var isolation=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (TransactionSynchronizationManager.isActualTransactionActive() && isolation==null)
            throw new IllegalStateException("Ambient transaction must declare READ_COMMITTED explicitly");
        if (isolation!=null && isolation!=TransactionDefinition.ISOLATION_READ_COMMITTED)
            throw new IllegalStateException("Ownership requires READ_COMMITTED isolation");
        return transactions.execute(status -> {
            if (!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
                throw new IllegalStateException("Ownership JDBC and transaction manager must share a DataSource");
            if(workerSession!=null)jdbc.queryForObject("select set_config('eodiisseo.collection.worker',?,true)",String.class,workerSession.toString());
            return action.apply(new JdbcTransaction());
        });
    }
    private void context(Claim c){
        if(workerSession!=null){
            jdbc.queryForObject("select set_config('eodiisseo.collection.run',?,true)",String.class,c.run().toString());
            jdbc.queryForObject("select set_config('eodiisseo.collection.claim',?,true)",String.class,c.claimId().toString());
            jdbc.queryForObject("select set_config('eodiisseo.collection.fence',?,true)",String.class,Long.toString(c.fence()));
        }
    }
    private final class JdbcTransaction implements Transaction {
        public Assignment assignment(UUID source) {
            var rows=jdbc.queryForList("""
                select a.* from data_source s join collection_source_assignment a
                  on a.source_id=s.source_id and a.epoch=s.collection_assignment_epoch
                join collection_contract c on c.contract_key=a.contract_key
                join collection_target t on t.target_id=a.target_id
                where s.source_id=? and s.endpoint_key=a.source_alias and s.active and a.approved and c.approved and t.approved
                """,source);
            if(rows.size()!=1)return null;
            var r=rows.getFirst();
            return new Assignment(source,(String)r.get("source_alias"),(UUID)r.get("target_id"),
                (String)r.get("contract_key"),(String)r.get("quota_scope_id"),((Number)r.get("epoch")).longValue(),
                (String)r.get("execution_owner"),(String)r.get("approval_reference"),Boolean.TRUE.equals(r.get("legacy_workers_quiesced")));
        }
        public void lock(Assignment a,String mode) {
            jdbc.queryForList("select pg_advisory_xact_lock(78161108)");
            var target=jdbc.queryForList("select target_id from collection_target where target_id=? for update",a.target());
            if(target.size()!=1)throw new IllegalStateException("Target missing");
            // All aliases are locked in deterministic order, not just the requesting source.
            jdbc.queryForList("""
                select s.source_id from data_source s where s.source_id=? or exists
                (select 1 from collection_source_assignment a where a.source_id=s.source_id and a.target_id=?)
                order by s.source_id for update
                """,a.source(),a.target());
            // Assignment administration must follow the same gate/target/source lock order.
            jdbc.queryForList("""
                select a.epoch,c.contract_key from collection_source_assignment a
                join collection_contract c on c.contract_key=a.contract_key
                where a.source_id=? and a.epoch=? for share of a,c
                """,a.source(),a.epoch());
        }
        public boolean blocked(Assignment a,String mode) {
            Integer count=jdbc.queryForObject("""
                select count(*) from data_source s where
                (s.source_id=? or exists(select 1 from collection_source_assignment a where a.source_id=s.source_id and a.target_id=?))
                and (exists(select 1 from collection_run r where r.source_id=s.source_id and r.mode=? and r.status='RUNNING')
                  or exists(select 1 from collection_job_lock l where l.source_id=s.source_id and l.mode=?))
                """,Integer.class,a.source(),a.target(),mode,mode);
            if(count!=null&&count>0)return true;
            Integer held=jdbc.queryForObject("select count(*) from collection_target_lease where target_id=? and mode=? and claim_id is not null",Integer.class,a.target(),mode);
            return held!=null&&held>0;
        }
        public Instant now(){return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();}
        public long nextFence(UUID target,String mode) {
            return Objects.requireNonNull(jdbc.queryForObject("""
                insert into collection_target_lease(target_id,mode,fence_token)
                values(?,?,1) on conflict(target_id,mode) do update
                set fence_token=collection_target_lease.fence_token+1
                where collection_target_lease.claim_id is null returning fence_token
                """,Long.class,target,mode));
        }
        public void insert(Claim c,Instant expiry) {
            context(c);var a=c.assignment();
            jdbc.update("""
                insert into collection_run(run_id,source_id,mode,started_at,status,heartbeat_at,trigger_type,
                  execution_owner,assignment_epoch,contract_key,target_id,quota_scope_id,claim_id,fence_token,ownership_phase)
                values(?,?,?,clock_timestamp(),'RUNNING',clock_timestamp(),'MANUAL',?,?,?,?,?,?,?,'RESERVED')
                """,c.run(),a.source(),c.mode(),a.owner(),a.epoch(),a.contract(),a.target(),a.quotaScope(),c.claimId(),c.fence());
            int n=jdbc.update("""
                update collection_target_lease set claim_id=?,run_id=?,lease_until=?
                where target_id=? and mode=? and fence_token=? and claim_id is null
                """,c.claimId(),c.run(),Timestamp.from(expiry),a.target(),c.mode(),c.fence());
            if(n!=1)throw new IllegalStateException("Lease allocation lost");
            jdbc.update("insert into collection_job_lock(source_id,mode,run_id,lease_until) values(?,?,?,?)",a.source(),c.mode(),c.run(),Timestamp.from(expiry));
        }
        public void assertCurrent(Claim c,boolean live) {
            context(c);var a=c.assignment();
            if(!a.equals(assignment(a.source())))throw new IllegalStateException("Assignment revoked or replaced");
            var rows=jdbc.queryForList("""
                select r.run_id from collection_run r
                join collection_target_lease t on t.run_id=r.run_id and t.target_id=r.target_id and t.mode=r.mode
                join collection_job_lock l on l.run_id=r.run_id and l.source_id=r.source_id and l.mode=r.mode
                where r.run_id=? and r.source_id=? and r.mode=? and r.status='RUNNING'
                  and r.execution_owner=? and r.assignment_epoch=? and r.contract_key=?
                  and r.target_id=? and r.quota_scope_id=? and r.claim_id=? and r.fence_token=?
                  and t.claim_id=r.claim_id and t.fence_token=r.fence_token and r.ownership_phase='RESERVED'
                  and (not ? or (t.lease_until>clock_timestamp() and l.lease_until>clock_timestamp()))
                for update of r,t,l
                """,c.run(),a.source(),c.mode(),a.owner(),a.epoch(),a.contract(),a.target(),a.quotaScope(),c.claimId(),c.fence(),live);
            if(rows.size()!=1)throw new IllegalStateException("Stale or unowned execution");
        }
        public void assertNoUncertainRequest(Claim c) {
            String table=jdbc.queryForObject("select to_regclass('collection_http_attempt')::text",String.class);
            if(table!=null && jdbc.queryForObject("select count(*) from collection_http_attempt where run_id=? and state in ('IN_FLIGHT','RECEIVED','UNKNOWN')",Long.class,c.run())>0)
                throw new IllegalStateException("External request completion is unresolved");
        }
        public Instant leaseUntil(Claim c) {
            return jdbc.queryForObject("select lease_until from collection_target_lease where target_id=? and mode=? and claim_id=? and fence_token=?",
                Timestamp.class,c.assignment().target(),c.mode(),c.claimId(),c.fence()).toInstant();
        }
        public void renew(Claim c,Instant expiry) {
            assertCurrent(c,true);
            int n=jdbc.update("update collection_target_lease set lease_until=? where target_id=? and mode=? and claim_id=? and fence_token=?",
                Timestamp.from(expiry),c.assignment().target(),c.mode(),c.claimId(),c.fence());
            int l=jdbc.update("update collection_job_lock set lease_until=? where source_id=? and mode=? and run_id=?",
                Timestamp.from(expiry),c.assignment().source(),c.mode(),c.run());
            int r=jdbc.update("update collection_run set heartbeat_at=clock_timestamp() where run_id=? and claim_id=? and fence_token=?",
                c.run(),c.claimId(),c.fence());
            if(n!=1||l!=1||r!=1)throw new IllegalStateException("Heartbeat lost");
        }
        public void finish(Claim c,Outcome outcome) {
            assertCurrent(c,outcome==Outcome.SUCCESS);
            int r=jdbc.update("""
                update collection_run set status=?,finished_at=clock_timestamp(),
                  error_code=case when ?='FAILED' then 'COLLECTION_OWNERSHIP_RESERVATION_ENDED' else null end
                where run_id=? and claim_id=? and fence_token=? and status='RUNNING'
                """,outcome.name(),outcome.name(),c.run(),c.claimId(),c.fence());
            int l=jdbc.update("delete from collection_job_lock where source_id=? and mode=? and run_id=?",c.assignment().source(),c.mode(),c.run());
            int t=jdbc.update("""
                update collection_target_lease set claim_id=null,run_id=null,lease_until=null
                where target_id=? and mode=? and claim_id=? and fence_token=?
                """,c.assignment().target(),c.mode(),c.claimId(),c.fence());
            if(r!=1||l!=1||t!=1)throw new IllegalStateException("Completion lost");
        }
    }
}
package com.disasterinfo.eodiisseo.collection.operations;

import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.collection.ownership.*;
import com.disasterinfo.eodiisseo.collection.execution.*;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Explicit disposable-only rehearsal. No bean, scheduler, property activation or HTTP management endpoint. */
public final class CollectionCutoverRehearsal {
    private final JdbcTemplate jdbc;private final JpaTransactionManager manager;private final CollectionOperationsService ops;
    private final CollectionReadiness readiness;private final CollectionOperationalEvidencePort evidence;
    private final Set<UUID> sources;private final List<CollectionProviderPort> providers;private final CollectionProjectionPort projection;
    private final CollectionCoordinator.Policy policy;private final MemberPrincipal actor;private final Duration lease;
    private UUID worker;private String fingerprint;
    public CollectionCutoverRehearsal(JdbcTemplate jdbc,JpaTransactionManager manager,CollectionOperationsService ops,
        CollectionReadiness readiness,CollectionOperationalEvidencePort evidence,Set<UUID> sources,
        List<CollectionProviderPort> providers,CollectionProjectionPort projection,CollectionCoordinator.Policy policy,
        MemberPrincipal actor,CollectionOperationMode mode,Duration lease){
        if(mode!=CollectionOperationMode.DISPOSABLE_AUDIT)throw new IllegalArgumentException("No production arming in 4B-1");
        mode.verify(jdbc.getDataSource());
        try(var c=jdbc.getDataSource().getConnection()){
            if(!c.getMetaData().getURL().matches("jdbc:postgresql://127\\.0\\.0\\.1:55433/collection4b1_[a-f0-9]{32}"))throw new IllegalArgumentException("Isolated 4B-1 database required");
        }catch(java.sql.SQLException fail){throw new IllegalStateException("Audit database unavailable");}
        this.jdbc=jdbc;this.manager=manager;this.ops=Objects.requireNonNull(ops);this.readiness=readiness;this.evidence=evidence;
        this.sources=Set.copyOf(sources);this.providers=List.copyOf(providers);this.projection=Objects.requireNonNull(projection);
        this.policy=policy;this.actor=actor;this.lease=Objects.requireNonNull(lease);
    }
    public static void requireSchema(JdbcTemplate jdbc){
        if(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2026111005' and success",Long.class)!=1
           ||jdbc.queryForObject("select to_regclass('collection_cutover_barrier')::text",String.class)==null)
            throw new IllegalStateException("Cutover migration missing");
    }
    /** Programmatic test operation only. Uses persisted OPS authorization and the complete 4A review chain. */
    public void begin(Duration lifetime,String reference){
        CollectionOperationsService.reference(reference);
        if(lifetime==null||lifetime.isNegative()||lifetime.isZero()||lifetime.compareTo(Duration.ofMinutes(30))>0)throw new IllegalArgumentException("Bounded rehearsal lifetime required");
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Rehearsal begin requires its own commit boundary");
        var handle=ops.withOps(actor,()->{
            requireSchema(jdbc);
            if(!readiness.inspect(sources).prepared())throw new IllegalStateException("Cutover prerequisites missing");
            var assignments=new JdbcCollectionOwnershipStore(jdbc,manager);
            var keys=new HashSet<String>();for(var source:sources){var a=assignments.transaction(tx->tx.assignment(source));keys.add(a.contract());}
            if(providers.size()!=keys.size()||!providers.stream().map(CollectionProviderPort::contractKey).collect(java.util.stream.Collectors.toSet()).equals(keys))throw new IllegalStateException("Exact approved plugin set required");
            if(providers.stream().anyMatch(p->!p.executable()||!p.evidence().equals(evidence.collectionContract(p.contractKey()))))throw new IllegalStateException("Independent provider evidence mismatch");
            String local=localFingerprint();
            var barrier=jdbc.queryForMap("select * from collection_cutover_barrier where singleton for update");
            UUID review=jdbc.queryForObject("select review_id from collection_transition_review where step='MIGRATIONS_VERIFIED' and valid_until>clock_timestamp() order by created_at desc,review_id desc limit 1",UUID.class);
            long generation=((Number)barrier.get("generation")).longValue()+1;
            UUID next=UUID.randomUUID();Instant now=jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant(),until=now.plus(lifetime);
            for(var e:evidence.current().values())if(e.validUntil().isBefore(until))until=e.validUntil();
            var expiry=jdbc.queryForObject("""
                select least((select min(valid_until) from collection_operation_approval p where p.source_id=any(?) and p.valid_until>clock_timestamp() and not exists(select 1 from collection_approval_revocation v where v.approval_id=p.approval_id)),
                (select min(valid_until) from collection_component_approval p where p.valid_until>clock_timestamp() and not exists(select 1 from collection_approval_revocation v where v.approval_id=p.approval_id)),
                (select min(q.ends_at) from collection_quota_window q where q.starts_at<=clock_timestamp() and q.ends_at>clock_timestamp() and exists(select 1 from collection_source_assignment a join data_source s on s.source_id=a.source_id and s.collection_assignment_epoch=a.epoch where a.source_id=any(?) and a.quota_scope_id=q.quota_scope_id)),
                (select expires_at from auth_session where session_id=?))
                """,Timestamp.class,sources.toArray(UUID[]::new),sources.toArray(UUID[]::new),actor.sessionId()).toInstant();
            if(expiry.isBefore(until))until=expiry;
            String dbFingerprint=jdbc.queryForObject("select collection_policy_fingerprint()",String.class);
            jdbc.update("update collection_cutover_barrier set state='AUDIT_UNIFIED',generation=?,review_id=?,changed_at=clock_timestamp() where singleton",generation,review);
            jdbc.update("insert into collection_worker_session values(?,?,?,?,?,?,?,?,clock_timestamp(),true)",next,generation,sources.toArray(UUID[]::new),dbFingerprint,local,actor.memberId(),actor.sessionId(),Timestamp.from(until));
            jdbc.update("insert into collection_cutover_history values(?,?,'AUDIT_UNIFIED',?,?,?,clock_timestamp())",UUID.randomUUID(),generation,actor.memberId(),actor.sessionId(),reference);
            // Publish the handle only after commit (outside this callback).
            return new Object[]{next,local};
        });
        worker=(UUID)handle[0];fingerprint=(String)handle[1];
    }
    public void freeze(String reference){CollectionOperationsService.reference(reference);ops.withOps(actor,()->{
        long generation=jdbc.queryForObject("select generation from collection_cutover_barrier where singleton for update",Long.class)+1;
        jdbc.update("update collection_cutover_barrier set state='FROZEN',generation=?,changed_at=clock_timestamp() where singleton",generation);
        jdbc.update("insert into collection_cutover_history values(?,?,'FROZEN',?,?,?,clock_timestamp())",UUID.randomUUID(),generation,actor.memberId(),actor.sessionId(),reference);return null;
    });}
    private String localFingerprint(){
        var parts=new ArrayList<String>();
        if(!evidence.legacyWorkersStopped()||!evidence.legacySchedulersDisabled())throw new IllegalStateException("Live legacy exclusion evidence lost");
        for(var kind:CollectionOperationalEvidencePort.Kind.values()){
            var e=evidence.current().get(kind);if(e==null||e.kind()!=kind||!e.verified()||!e.validUntil().isAfter(Instant.now()))throw new IllegalStateException("Operational witness expired");parts.add(e.toString());
        }
        for(var p:providers.stream().sorted(Comparator.comparing(CollectionProviderPort::contractKey)).toList()){
            if(!p.evidence().equals(evidence.collectionContract(p.contractKey())))throw new IllegalStateException("Provider contract changed");parts.add(p.contractKey()+p.evidence());
        }
        var archive=Objects.requireNonNull(evidence.archivePolicy());parts.add(archive.retention()+"|"+archive.approval());
        var windows=jdbc.queryForList("""
            select q.quota_scope_id,q.starts_at,q.ends_at,q.request_limit,q.min_interval_ms,q.approval_reference from collection_quota_window q
            where q.starts_at<=clock_timestamp() and q.ends_at>clock_timestamp() and exists
            (select 1 from collection_source_assignment a join data_source s on s.source_id=a.source_id and s.collection_assignment_epoch=a.epoch
             where a.source_id=any(?) and a.quota_scope_id=q.quota_scope_id) order by q.quota_scope_id,q.starts_at
            """,(Object)sources.toArray(UUID[]::new));
        int scopes=jdbc.queryForObject("select count(distinct a.quota_scope_id) from collection_source_assignment a join data_source s on s.source_id=a.source_id and s.collection_assignment_epoch=a.epoch where a.source_id=any(?)",Integer.class,(Object)sources.toArray(UUID[]::new));
        if(scopes==0||windows.size()!=scopes)throw new IllegalStateException("Current approved quota window required");
        for(var w:windows)if(!evidence.quotaApproved((String)w.get("quota_scope_id"),((Timestamp)w.get("starts_at")).toInstant(),((Timestamp)w.get("ends_at")).toInstant(),((Number)w.get("request_limit")).longValue(),((Number)w.get("min_interval_ms")).intValue(),(String)w.get("approval_reference")))throw new IllegalStateException("Quota approval withdrawn");
        return CollectionOperationsService.hash(parts.toString());
    }
    private void check(){ops.withOps(actor,()->{
        if(worker==null||!Objects.equals(fingerprint,localFingerprint()))throw new IllegalStateException("Rehearsal not active or configuration changed");
        jdbc.queryForObject("select set_config('eodiisseo.collection.worker',?,true)",String.class,worker.toString());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select collection_worker_context_valid()",Boolean.class)))throw new IllegalStateException("Worker generation or approvals invalid");return null;
    });}
    public CollectionCoordinator.Result collect(UUID source,LocalDate from,LocalDate to,int rows){
        try{if(!sources.contains(source))throw new IllegalStateException("Source outside scope");check();}catch(RuntimeException denied){return new CollectionCoordinator.Result(null,CollectionExecutionStore.State.HELD,"REHEARSAL_READINESS_HELD");}
        var ownership=new CollectionOwnership(new JdbcCollectionOwnershipStore(jdbc,manager,worker),new CollectionContractRegistry(),lease);
        var guarded=providers.stream().map(p->new CollectionProviderPort(){
            public String contractKey(){return p.contractKey();}public boolean executable(){return p.executable();}
            public Evidence evidence(){return p.evidence();}public void validateRequest(Request r){check();p.validateRequest(r);}
            public Reply exchange(Request r){check();return p.exchange(r);}public Page decode(Reply reply,Request r){check();return p.decode(reply,r);}
            public String identity(Map<String,Object> row){return p.identity(row);}
        }).map(CollectionProviderPort.class::cast).toList();
        CollectionProjectionPort guardedProjection=new CollectionProjectionPort(){
            public Result projectVerifiedArchive(Claim c){check();var result=projection.projectVerifiedArchive(c);check();
                long mismatched=jdbc.queryForObject("""
                    select count(*) from collection_projection_receipt r where r.run_id=? and r.result not in ('UNLINKED') and not exists
                    (select 1 from collection_operation_approval p where p.source_id=? and p.epoch=? and p.target_id=r.target_id and p.contract_key=r.contract_key
                    and p.canonical_source_id=r.canonical_source_id and p.validation_version=r.validation_version and p.evidence_reference=r.validation_reference
                    and p.projection_allowed and p.coverage_allowed and p.valid_until>clock_timestamp()
                    and not exists(select 1 from collection_approval_revocation v where v.approval_id=p.approval_id))
                    """,Long.class,c.run(),c.assignment().source(),c.assignment().epoch());
                if(mismatched!=0)throw new ReviewRequired("OPERATION_APPROVAL_MISMATCH");return result;
            }
            public void recordRolledBack(Claim c,String reason){check();projection.recordRolledBack(c,reason);}
        };
        return new CollectionCoordinator(ownership,new JdbcCollectionExecutionStore(jdbc,manager,worker),guarded,policy,evidence.archivePolicy(),guardedProjection).collect(source,from,to,rows);
    }
}
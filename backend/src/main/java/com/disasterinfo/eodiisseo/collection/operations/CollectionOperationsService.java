package com.disasterinfo.eodiisseo.collection.operations;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import com.disasterinfo.eodiisseo.collection.ownership.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Explicit opt-in administration. Gate -> member -> session -> role -> run/attempt locks.
 * No remote I/O, no owner inference, no successful-run rewrite and no quota refunds. */
public final class CollectionOperationsService {
    public record Snapshot(UUID run, String digest, Map<String,Object> execution,
                           List<Map<String,Object>> sourceLocks,List<Map<String,Object>> targetLocks,List<Map<String,Object>> attempts) {}
    public record Resolution(UUID requestId,UUID run,String expectedSnapshot,String decision,String evidenceReference,
                             UUID stopReview,String completionReference) {}
    public record Operation(UUID source,UUID canonicalSource,String validationVersion,String reference,
                            boolean projection,boolean coverage,Instant validUntil) {}
    private final JdbcTemplate jdbc;
    private final JdbcCollectionOwnershipStore transactions;
    private final SessionAccessService sessions;
    private final CollectionOperationMode mode;
    private final CollectionOperationalEvidencePort evidence;
    public CollectionOperationsService(JdbcTemplate jdbc,PlatformTransactionManager manager,SessionAccessService sessions,
            CollectionOperationMode mode,CollectionOperationalEvidencePort evidence) {
        this.jdbc=jdbc;this.transactions=new JdbcCollectionOwnershipStore(jdbc,manager);this.sessions=sessions;
        this.mode=Objects.requireNonNull(mode);this.evidence=evidence;mode.verify(jdbc.getDataSource());
    }
    private <T>T authorized(MemberPrincipal actor,Supplier<T> work) {
        if(actor==null)throw ApiProblem.unauthorized();
        return transactions.transaction(tx->{
            jdbc.queryForList("select pg_advisory_xact_lock(78161108)");
            var result=new java.util.concurrent.atomic.AtomicReference<T>();
            sessions.runWithActiveSession(actor.memberId(),actor.sessionId(),()->{
                if(jdbc.queryForList("select member_id from member_role where member_id=? and role='OPS' for share",actor.memberId()).size()!=1)
                    throw ApiProblem.forbidden();
                requireFreshSession(actor);result.set(work.get());requireFreshSession(actor);
            });return result.get();
        });
    }
    <T>T withOps(MemberPrincipal actor,Supplier<T> work){return authorized(actor,()->{
        jdbc.queryForObject("select set_config('eodiisseo.collection.ops_member',?,true)",String.class,actor.memberId().toString());
        jdbc.queryForObject("select set_config('eodiisseo.collection.ops_session',?,true)",String.class,actor.sessionId().toString());
        return work.get();
    });}
    private void requireFreshSession(MemberPrincipal actor){
        // now() is transaction-start time: waiting for the Collection gate must not extend session validity.
        if(jdbc.queryForObject("""
            select count(*) from auth_session s join member m using(member_id)
            where s.session_id=? and s.member_id=? and s.revoked_at is null and m.status='ACTIVE'
            and s.expires_at>clock_timestamp()
            """,Long.class,actor.sessionId(),actor.memberId())!=1)throw ApiProblem.unauthorized();
    }
    public Snapshot inspect(MemberPrincipal actor,UUID run){return authorized(actor,()->snapshot(run));}
    public List<Map<String,Object>> investigate(MemberPrincipal actor){return authorized(actor,()->jdbc.queryForList("""
        select r.run_id,r.source_id,r.status,r.execution_owner,r.claim_id,r.fence_token,r.started_at,
        exists(select 1 from collection_job_lock l where l.run_id=r.run_id) as source_locked,
        exists(select 1 from collection_target_lease l where l.run_id=r.run_id) as target_locked,
        exists(select 1 from collection_http_attempt a where a.run_id=r.run_id and a.state in ('IN_FLIGHT','RECEIVED','UNKNOWN')) as uncertain
        from collection_run r where r.status='RUNNING'
        or exists(select 1 from collection_job_lock l where l.run_id=r.run_id)
        or exists(select 1 from collection_target_lease l where l.run_id=r.run_id)
        or exists(select 1 from collection_http_attempt a where a.run_id=r.run_id and a.state in ('IN_FLIGHT','RECEIVED','UNKNOWN'))
        order by r.started_at,r.run_id limit 100
        """));}
    private Snapshot snapshot(UUID run){
        var rows=jdbc.queryForList("""
            select run_id,source_id,mode,status,execution_owner,assignment_epoch,contract_key,target_id,quota_scope_id,
            claim_id,fence_token,ownership_phase,started_at,heartbeat_at,finished_at from collection_run where run_id=? for update
            """,run);
        if(rows.size()!=1)throw ApiProblem.missing("COLLECTION_RUN_NOT_FOUND");
        var source=jdbc.queryForList("select source_id,mode,run_id,lease_until from collection_job_lock where run_id=? order by source_id,mode for update",run);
        var target=jdbc.queryForList("select target_id,mode,run_id,claim_id,fence_token,lease_until from collection_target_lease where run_id=? order by target_id,mode for update",run);
        // Late HTTP journal callbacks lock execution before attempt. Preserve that order to avoid deadlocks.
        var http=jdbc.queryForList("select run_id,state,domain_phase,projection_required from collection_http_execution where run_id=? for update",run);
        for(var h:http){rows.getFirst().put("http_state",h.get("state"));rows.getFirst().put("domain_phase",h.get("domain_phase"));rows.getFirst().put("projection_required",h.get("projection_required"));}
        var attempts=jdbc.queryForList("select attempt_id,page_no,attempt_no,state,http_status,started_at,finished_at from collection_http_attempt where run_id=? order by page_no,attempt_no for update",run);
        String digest=hash(rows.toString()+source+target+attempts);
        return new Snapshot(run,digest,Collections.unmodifiableMap(rows.getFirst()),List.copyOf(source),List.copyOf(target),List.copyOf(attempts));
    }
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    static String reference(String ref){if(ref==null||!ref.matches("[A-Z0-9][A-Z0-9_.:-]{3,159}"))throw ApiProblem.bad("Opaque reviewed evidence reference required");return ref;}
    private CollectionOperationalEvidencePort.Evidence witness(CollectionOperationalEvidencePort.Kind kind){
        var e=evidence==null?null:evidence.current().get(kind);
        if(e==null||e.kind()!=kind||!e.verified()||!mode.accepts(e.synthetic())||!e.validUntil().isAfter(now()))throw ApiProblem.conflict("OPERATIONAL_EVIDENCE_REQUIRED");
        reference(e.reference());if(kind==CollectionOperationalEvidencePort.Kind.LEGACY_FLEET&&(!evidence.legacyWorkersStopped()||!evidence.legacySchedulersDisabled()))throw ApiProblem.conflict("LEGACY_STOP_PROOF_REQUIRED");return e;
    }
    private Instant now(){return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();}
    public UUID review(MemberPrincipal actor,String step,UUID parent,String ref,Instant until){return authorized(actor,()->{
        reference(ref);if(until==null||!until.isAfter(now()))throw ApiProblem.bad("Future review expiry required");
        var fleet=witness(CollectionOperationalEvidencePort.Kind.LEGACY_FLEET);
        if(until.isAfter(fleet.validUntil()))throw ApiProblem.bad("Review cannot outlive deployment evidence");
        if(step==null||!Set.of("STOP_VERIFIED","DRAIN_VERIFIED","MIGRATIONS_VERIFIED","FROZEN").contains(step))throw ApiProblem.bad("Invalid preparation step");
        String previous=step.equals("DRAIN_VERIFIED")?"STOP_VERIFIED":step.equals("MIGRATIONS_VERIFIED")?"DRAIN_VERIFIED":null;
        if(previous!=null)requireReview(parent,previous);
        if(step.equals("DRAIN_VERIFIED")||step.equals("MIGRATIONS_VERIFIED"))requireDrained();
        if(step.equals("MIGRATIONS_VERIFIED"))CollectionReadiness.requireSchema(jdbc);
        UUID id=UUID.randomUUID();jdbc.update("""
            insert into collection_transition_review(review_id,parent_review_id,step,reviewed_by,session_id,evidence_reference,evidence_fingerprint,valid_until,synthetic)
            values(?,?,?,?,?,?,?,?,?)
            """,id,parent,step,actor.memberId(),actor.sessionId(),ref,fleet.fingerprint(),Timestamp.from(until),mode==CollectionOperationMode.DISPOSABLE_AUDIT);
        return id;
    });}
    void requireDrained(){if(jdbc.queryForObject("""
        select (select count(*) from collection_run where status='RUNNING')+
        (select count(*) from collection_job_lock)+(select count(*) from collection_target_lease where claim_id is not null)+
        (select count(*) from collection_http_attempt where state in ('IN_FLIGHT','RECEIVED','UNKNOWN'))
        """,Long.class)!=0)throw ApiProblem.conflict("COLLECTION_DRAIN_REQUIRED");}
    private void requireReview(UUID id,String step){
        var fleet=witness(CollectionOperationalEvidencePort.Kind.LEGACY_FLEET);
        if(id==null||jdbc.queryForObject("""
            select count(*) from collection_transition_review where review_id=? and step=? and valid_until>clock_timestamp()
            and evidence_fingerprint=? and (? or not synthetic)
            and not exists(select 1 from collection_transition_review where step='FROZEN' and created_at>=(select created_at from collection_transition_review where review_id=?))
            """,Long.class,id,step,fleet.fingerprint(),mode==CollectionOperationMode.DISPOSABLE_AUDIT,id)!=1)
            throw ApiProblem.conflict("FRESH_TRANSITION_REVIEW_REQUIRED");
    }
    public UUID resolve(MemberPrincipal actor,Resolution request){return authorized(actor,()->{
        if(request==null)throw ApiProblem.bad("Resolution required");reference(request.evidenceReference());
        if(request.requestId()==null||(request.decision()==null||!Set.of("KEEP_HELD","RELEASE_CONFIRMED_STOPPED").contains(request.decision())))throw ApiProblem.bad("Invalid resolution");
        if(jdbc.queryForObject("select count(*) from collection_resolution_detail where request_id=?",Long.class,request.requestId())!=0)
            throw ApiProblem.conflict("RESOLUTION_ALREADY_RECORDED");
        var s=snapshot(request.run());if(!s.digest().equals(request.expectedSnapshot()))throw ApiProblem.conflict("COLLECTION_STATE_CHANGED");
        boolean release=request.decision().equals("RELEASE_CONFIRMED_STOPPED");
        if(release){
            requireReview(request.stopReview(),"STOP_VERIFIED");
            // A definite human-reviewed external completion/stop proof is mandatory, not a timeout.
            reference(request.completionReference());
            if(!evidence.completionVerified(request.run(),s.digest(),request.completionReference()))throw ApiProblem.conflict("REQUEST_COMPLETION_PROOF_REQUIRED");
            if(jdbc.queryForObject("select count(*) from collection_run_resolution where run_id=? and decision='RELEASE_CONFIRMED_STOPPED'",Long.class,request.run())!=0)
                throw ApiProblem.conflict("RUN_ALREADY_RELEASED");
            for(var l:s.sourceLocks())if(!Objects.equals(l.get("source_id"),s.execution().get("source_id"))||!Objects.equals(l.get("mode"),s.execution().get("mode")))throw ApiProblem.conflict("LOCK_IDENTITY_CONFLICT");
            for(var l:s.targetLocks())if(!Objects.equals(l.get("target_id"),s.execution().get("target_id"))||!Objects.equals(l.get("mode"),s.execution().get("mode"))||!Objects.equals(l.get("claim_id"),s.execution().get("claim_id"))||!Objects.equals(l.get("fence_token"),s.execution().get("fence_token")))throw ApiProblem.conflict("LOCK_IDENTITY_CONFLICT");
        }
        UUID id=UUID.randomUUID();jdbc.update("insert into collection_run_resolution values(?,?,?,?,?,?,clock_timestamp())",id,request.run(),actor.memberId(),request.decision(),request.evidenceReference(),release);
        jdbc.update("insert into collection_resolution_detail values(?,?,?,?,?,?,?)",id,request.requestId(),s.digest(),release?request.stopReview():null,release?request.completionReference():null,actor.sessionId(),mode==CollectionOperationMode.DISPOSABLE_AUDIT);
        if(release){
            jdbc.queryForObject("select set_config('eodiisseo.collection.ops_member',?,true)",String.class,actor.memberId().toString());
            jdbc.queryForObject("select set_config('eodiisseo.collection.ops_session',?,true)",String.class,actor.sessionId().toString());
            jdbc.queryForObject("select set_config('eodiisseo.collection.resolution',?,true)",String.class,id.toString());
            jdbc.update("update collection_http_attempt set state='REJECTED',finished_at=coalesce(finished_at,clock_timestamp()) where run_id=? and state in ('IN_FLIGHT','RECEIVED','UNKNOWN')",request.run());
            jdbc.update("update collection_http_execution set state='FAILED',reason='OPS_CONFIRMED_STOPPED',domain_phase=case when projection_required then 'DOMAIN_FAILED' else domain_phase end where run_id=? and state<>'COMPLETE'",request.run());
            jdbc.update("update collection_run set status='FAILED',finished_at=clock_timestamp(),error_code='OPS_CONFIRMED_STOPPED' where run_id=? and status='RUNNING'",request.run());
            jdbc.update("delete from collection_job_lock where run_id=?",request.run());
            jdbc.update("update collection_target_lease set run_id=null,claim_id=null,lease_until=null where run_id=?",request.run());
        }
        return id;
    });}
    public UUID approveComponent(MemberPrincipal actor,CollectionOperationalEvidencePort.Kind kind){return authorized(actor,()->{
        var e=witness(kind);UUID id=UUID.randomUUID();jdbc.update("insert into collection_component_approval values(?,?,?,?,?,?,?,?,?,clock_timestamp())",id,kind.name(),e.version(),e.fingerprint(),e.reference(),Timestamp.from(e.validUntil()),actor.memberId(),actor.sessionId(),mode==CollectionOperationMode.DISPOSABLE_AUDIT);return id;
    });}
    public UUID approveOperation(MemberPrincipal actor,Operation operation){return authorized(actor,()->{
        if(operation==null)throw ApiProblem.bad("Operation approval required");reference(operation.reference());if(operation.validationVersion()==null||operation.validationVersion().isBlank()||operation.validationVersion().length()>100||operation.validUntil()==null||!operation.validUntil().isAfter(now()))throw ApiProblem.bad("Version and review validity required");
        witness(CollectionOperationalEvidencePort.Kind.DOMAIN_POLICY);
        var a=transactions.transaction(tx->tx.assignment(operation.source()));if(a==null)throw ApiProblem.conflict("APPROVED_ASSIGNMENT_REQUIRED");
        transactions.transaction(tx->{tx.lock(a,"LIVE");return null;});
        new CollectionContractRegistry().require(a.contract(),a.alias());
        if(a.contract().equals("MOIS_WILDFIRE")||a.contract().equals("KMA_WARNING_LIST"))throw ApiProblem.conflict("DOMAIN_CONTRACT_UNRESOLVED");
        var canonical=transactions.transaction(tx->tx.assignment(operation.canonicalSource()));
        if(canonical==null||!canonical.target().equals(a.target())||!canonical.contract().equals(a.contract())||!canonical.quotaScope().equals(a.quotaScope()))throw ApiProblem.conflict("CANONICAL_ASSIGNMENT_MISMATCH");
        if(operation.coverage()&&!operation.projection())throw ApiProblem.bad("Coverage requires projection approval");
        UUID id=UUID.randomUUID();jdbc.update("""
            insert into collection_operation_approval values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp())
            """,id,a.source(),a.epoch(),a.target(),a.contract(),a.quotaScope(),operation.canonicalSource(),operation.validationVersion(),operation.reference(),operation.projection(),operation.coverage(),Timestamp.from(operation.validUntil()),actor.memberId(),actor.sessionId(),mode==CollectionOperationMode.DISPOSABLE_AUDIT);return id;
    });}
    public void revoke(MemberPrincipal actor,UUID id,String ref){authorized(actor,()->{
        reference(ref);if(jdbc.queryForObject("select (select count(*) from collection_operation_approval where approval_id=?)+(select count(*) from collection_component_approval where approval_id=?)",Long.class,id,id)!=1)throw ApiProblem.missing("APPROVAL_NOT_FOUND");
        if(jdbc.queryForObject("select count(*) from collection_approval_revocation where approval_id=?",Long.class,id)!=0)throw ApiProblem.conflict("APPROVAL_ALREADY_REVOKED");
        jdbc.update("insert into collection_approval_revocation values(?,?,?,?,clock_timestamp())",id,actor.memberId(),actor.sessionId(),ref);return null;
    });}
}
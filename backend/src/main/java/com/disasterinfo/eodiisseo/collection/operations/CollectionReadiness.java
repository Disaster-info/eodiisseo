package com.disasterinfo.eodiisseo.collection.operations;

import com.disasterinfo.eodiisseo.collection.ownership.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;

/** Read-only preparation checks, never an authorization to execute or switch workers. */
public final class CollectionReadiness {
    public record Report(boolean prepared,boolean executionAllowed,List<String> blockers) {}
    private final JdbcTemplate jdbc; private final JdbcCollectionOwnershipStore transactions;
    private final JpaTransactionManager manager; private final Environment env;
    private final CollectionOperationalEvidencePort evidence;private final CollectionOperationMode mode;
    public CollectionReadiness(JdbcTemplate jdbc,PlatformTransactionManager manager,Environment env,
                               CollectionOperationalEvidencePort evidence,CollectionOperationMode mode){
        this.jdbc=jdbc;this.transactions=new JdbcCollectionOwnershipStore(jdbc,manager);
        this.manager=manager instanceof JpaTransactionManager j?j:null;this.env=env;this.evidence=evidence;this.mode=mode;mode.verify(jdbc.getDataSource());
    }
    public static void requireSchema(JdbcTemplate jdbc){
        for(String version:List.of("2026111001","2026111002","2026111003","2026111004"))
            if(jdbc.queryForObject("select count(*) from flyway_schema_history where version=? and success",Long.class,version)!=1)
                throw new IllegalStateException("Required approved Collection migration missing");
        for(String table:List.of("collection_run_resolution","collection_quota_window","collection_projection_receipt","collection_domain_cursor","collection_transition_review","collection_operation_approval","collection_resolution_detail"))
            if(jdbc.queryForObject("select to_regclass(?)::text",String.class,table)==null)throw new IllegalStateException("Required Collection table missing");
    }
    public Report inspect(Collection<UUID> sources){
        var reasons=new ArrayList<String>();
        try{requireSchema(jdbc);}catch(RuntimeException missing){return new Report(false,false,List.of("MIGRATIONS_REQUIRED"));}
        if(Boolean.parseBoolean(env.getProperty("COLLECTION_WORKER_ENABLED","false"))||Boolean.parseBoolean(env.getProperty("eodiisseo.disaster.collection.enabled","false")))reasons.add("LEGACY_SCHEDULER_ENABLED");
        if(manager==null)reasons.add("SHARED_JPA_MANAGER_REQUIRED");
        if(evidence==null)reasons.add("OPERATIONAL_EVIDENCE_REQUIRED");
        else {
            if(!evidence.legacyWorkersStopped()||!evidence.legacySchedulersDisabled())reasons.add("LEGACY_STOP_PROOF_REQUIRED");
            if(evidence.transport()==null)reasons.add("REVIEWED_SENDER_REQUIRED");
            if(evidence.archivePolicy()==null)reasons.add("REVIEWED_ARCHIVE_POLICY_REQUIRED");
        }
        if(sources==null||sources.isEmpty())reasons.add("EXPLICIT_SOURCES_REQUIRED");
        return transactions.transaction(tx->{
            jdbc.queryForList("select pg_advisory_xact_lock(78161108)");
            if(manager!=null){
                var holder=(EntityManagerHolder)TransactionSynchronizationManager.getResource(manager.getEntityManagerFactory());
                if(holder==null||((Number)holder.getEntityManager().createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue()!=jdbc.queryForObject("select pg_backend_pid()",Integer.class))reasons.add("JPA_JDBC_TRANSACTION_MISMATCH");
            }
            Instant now=jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();
            var live=evidence==null?Map.<CollectionOperationalEvidencePort.Kind,CollectionOperationalEvidencePort.Evidence>of():evidence.current();
            for(var kind:CollectionOperationalEvidencePort.Kind.values()){
                var e=live.get(kind);
                if(e==null||e.kind()!=kind||!e.verified()||!mode.accepts(e.synthetic())||!e.validUntil().isAfter(now)){reasons.add(kind+"_EVIDENCE_REQUIRED");continue;}
                if(jdbc.queryForObject("""
                    select count(*) from collection_component_approval p where p.kind=? and p.version=? and p.fingerprint=? and p.evidence_reference=?
                    and p.valid_until>clock_timestamp() and (? or not p.synthetic)
                    and not exists(select 1 from collection_approval_revocation r where r.approval_id=p.approval_id)
                    """,Long.class,kind.name(),e.version(),e.fingerprint(),e.reference(),mode==CollectionOperationMode.DISPOSABLE_AUDIT)<1)reasons.add(kind+"_APPROVAL_REQUIRED");
            }
            var fleet=live.get(CollectionOperationalEvidencePort.Kind.LEGACY_FLEET);
            if(fleet==null||jdbc.queryForObject("""
                select count(*) from collection_transition_review m
                join collection_transition_review d on d.review_id=m.parent_review_id and d.step='DRAIN_VERIFIED'
                join collection_transition_review s on s.review_id=d.parent_review_id and s.step='STOP_VERIFIED'
                where m.step='MIGRATIONS_VERIFIED' and m.valid_until>clock_timestamp() and d.valid_until>clock_timestamp() and s.valid_until>clock_timestamp()
                and m.evidence_fingerprint=? and d.evidence_fingerprint=m.evidence_fingerprint and s.evidence_fingerprint=m.evidence_fingerprint
                and (? or (not m.synthetic and not d.synthetic and not s.synthetic))
                and not exists(select 1 from collection_transition_review f where f.step='FROZEN' and f.created_at>=s.created_at)
                """,Long.class,fleet==null?"":fleet.fingerprint(),mode==CollectionOperationMode.DISPOSABLE_AUDIT)<1)reasons.add("FRESH_STOP_DRAIN_MIGRATION_REVIEW_REQUIRED");
            if(jdbc.queryForObject("""
                select (select count(*) from collection_run where status='RUNNING')+(select count(*) from collection_job_lock)+
                (select count(*) from collection_target_lease where claim_id is not null)+
                (select count(*) from collection_http_attempt where state in ('IN_FLIGHT','RECEIVED','UNKNOWN'))
                """,Long.class)!=0)reasons.add("RUNS_LOCKS_OR_REQUESTS_NOT_DRAINED");
            if(sources!=null)for(UUID source:sources){
                var a=tx.assignment(source);
                if(a==null){reasons.add("SOURCE_ASSIGNMENT_REQUIRED");continue;}
                try{new CollectionContractRegistry().require(a.contract(),a.alias());}catch(IllegalArgumentException mismatch){reasons.add("SOURCE_CONTRACT_MISMATCH");continue;}
                if(!a.legacyWorkersQuiesced()||!a.owner().equals("COMMON_COLLECTION_V1"))reasons.add("ASSIGNMENT_OWNER_UNAPPROVED");
                if(a.contract().equals("MOIS_WILDFIRE")||a.contract().equals("KMA_WARNING_LIST"))reasons.add("DOMAIN_CONTRACT_UNRESOLVED");
                var approvals=jdbc.queryForList("""
                    select p.* from collection_operation_approval p where p.source_id=? and p.epoch=? and p.target_id=? and p.contract_key=? and p.quota_scope_id=?
                    and p.projection_allowed and p.coverage_allowed and p.valid_until>clock_timestamp() and (? or not p.synthetic)
                    and not exists(select 1 from collection_approval_revocation r where r.approval_id=p.approval_id)
                    """,source,a.epoch(),a.target(),a.contract(),a.quotaScope(),mode==CollectionOperationMode.DISPOSABLE_AUDIT);
                if(approvals.size()!=1)reasons.add("UNIQUE_OPERATION_APPROVAL_REQUIRED");
                else {
                    var canonical=tx.assignment((UUID)approvals.getFirst().get("canonical_source_id"));
                    if(canonical==null||!canonical.target().equals(a.target())||!canonical.contract().equals(a.contract())||!canonical.quotaScope().equals(a.quotaScope()))reasons.add("CANONICAL_SOURCE_MISMATCH");
                    if(jdbc.queryForObject("""
                        select count(distinct canonical_source_id) from collection_operation_approval p where target_id=? and valid_until>clock_timestamp()
                        and not exists(select 1 from collection_approval_revocation r where r.approval_id=p.approval_id)
                        """,Long.class,a.target())!=1)reasons.add("ALIAS_CANONICAL_CONFLICT");
                }
                var windows=jdbc.queryForList("select * from collection_quota_window where quota_scope_id=? and starts_at<=clock_timestamp() and ends_at>clock_timestamp() and reserved<request_limit",a.quotaScope());
                if(windows.size()!=1)reasons.add("UNIQUE_APPROVED_QUOTA_REQUIRED");
                else {
                    var w=windows.getFirst();
                    if(evidence==null||!evidence.quotaApproved(a.quotaScope(),((Timestamp)w.get("starts_at")).toInstant(),((Timestamp)w.get("ends_at")).toInstant(),((Number)w.get("request_limit")).longValue(),((Number)w.get("min_interval_ms")).intValue(),(String)w.get("approval_reference")))reasons.add("QUOTA_POLICY_APPROVAL_REQUIRED");
                }
                var contract=evidence==null?null:evidence.collectionContract(a.contract());
                if(contract==null||!contract.boundedRange()||!contract.stableSnapshot()||!contract.incremental())reasons.add("PROVIDER_COMPLETENESS_UNVERIFIED");
            }
            return new Report(reasons.isEmpty(),false,List.copyOf(new LinkedHashSet<>(reasons)));
        });
    }
}
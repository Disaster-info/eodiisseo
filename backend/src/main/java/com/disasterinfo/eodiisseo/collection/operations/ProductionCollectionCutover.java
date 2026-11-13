package com.disasterinfo.eodiisseo.collection.operations;

import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Explicit control-plane preparation only; no bean, endpoint, scheduler, or Worker replacement. */
public final class ProductionCollectionCutover {
    private final JdbcTemplate jdbc;private final CollectionOperationsService ops;private final JdbcCollectionAssurance assurance;private final VerifiedCollectionOperationalEvidence evidence;
    public ProductionCollectionCutover(JdbcTemplate jdbc,CollectionOperationsService ops,JdbcCollectionAssurance assurance,VerifiedCollectionOperationalEvidence evidence){this.jdbc=Objects.requireNonNull(jdbc);this.ops=Objects.requireNonNull(ops);this.assurance=Objects.requireNonNull(assurance);this.evidence=Objects.requireNonNull(evidence);if(!ops.usesDataSource(jdbc.getDataSource())||!assurance.usesDataSource(jdbc.getDataSource()))throw new IllegalArgumentException("Single control-plane DataSource required");}
    public void freeze(MemberPrincipal actor,String reference){CollectionOperationsService.reference(reference);ops.withOps(actor,()->{jdbc.queryForList("select collection_production.freeze(?,?,?)",actor.memberId(),actor.sessionId(),reference);return null;});}
    /** Every signed envelope is verified again under the same gate. No boolean-property approval. */
    public void seal(MemberPrincipal actor,UUID migrationReview,List<UUID> artifactIds,Set<UUID> sources,Instant until,String reference){
        CollectionOperationsService.reference(reference);if(!evidence.legacyWorkersStopped()||!evidence.legacySchedulersDisabled()||evidence.transport()==null||evidence.archivePolicy()==null)throw new IllegalStateException("Fresh reviewed fleet, sender and archive evidence required");if(sources.isEmpty()||artifactIds.isEmpty()||until==null)throw new IllegalArgumentException("Explicit reviewed scope required");
        ops.withOps(actor,()->assurance.stable(view->{
            for(var id:artifactIds){var rows=jdbc.queryForList("select kind,scope_key from collection_assurance_artifact where artifact_id=?",id);if(rows.size()!=1)throw new IllegalStateException("Exact signed artifact required");var row=rows.getFirst();var active=view.find(CollectionAssurance.Kind.valueOf((String)row.get("kind")),(String)row.get("scope_key")).orElseThrow(()->new IllegalStateException("Approval expired or revoked"));if(!id.equals(active.id())||active.until().isBefore(until))throw new IllegalStateException("Approval does not cover transition");}
            for(UUID source:sources){var contract=jdbc.queryForObject("select a.contract_key from collection_source_assignment a join data_source s on s.source_id=a.source_id and s.collection_assignment_epoch=a.epoch where s.source_id=?",String.class,source);if(assurance.mode()==CollectionOperationMode.PRODUCTION&&!new CollectionOfficialAdmissionCatalog().productionAdmissible(contract))throw new IllegalStateException("Official provider contract remains blocked");}
            jdbc.queryForList("select collection_production.seal(?,?,?,?,?,?)",actor.memberId(),actor.sessionId(),migrationReview,artifactIds.toArray(UUID[]::new),Timestamp.from(until),reference);return null;
        }));
    }
    public void revokeWorker(MemberPrincipal actor,UUID worker,String reference){CollectionOperationsService.reference(reference);ops.withOps(actor,()->{jdbc.queryForList("select collection_production.revoke_worker(?,?,?,?)",actor.memberId(),actor.sessionId(),worker,reference);return null;});}
    public void resolveHeld(MemberPrincipal actor,UUID resolution,UUID completion){
        ops.withOps(actor,()->assurance.stable(view->{
            var rows=jdbc.queryForList("select r.run_id,d.expected_snapshot,d.completion_reference,a.kind,a.scope_key from collection_run_resolution r join collection_resolution_detail d using(resolution_id) cross join collection_assurance_artifact a where r.resolution_id=? and a.artifact_id=?",resolution,completion);
            if(rows.size()!=1)throw new IllegalStateException("Exact completion proof required");var r=rows.getFirst();
            var a=view.find(CollectionAssurance.Kind.HTTP_COMPLETION,(String)r.get("scope_key")).orElseThrow(()->new IllegalStateException("Completion approval expired"));
            if(!completion.equals(a.id())||!"HTTP_COMPLETION".equals(r.get("kind"))||!evidence.completionVerified((UUID)r.get("run_id"),(String)r.get("expected_snapshot"),(String)r.get("completion_reference")))throw new IllegalStateException("Verified request completion required");
            jdbc.queryForList("select collection_production.resolve_held(?,?,?,?)",actor.memberId(),actor.sessionId(),resolution,completion);return null;
        }));
    }
    /** Capability is returned only to trusted gateway assembly; its plaintext is never persisted or logged. */
    public ProductionCollectionWriteGateway.Permit issueWorker(MemberPrincipal actor,Set<UUID> sources,Instant until){
        if(sources==null||sources.isEmpty()||until==null||!evidence.legacyWorkersStopped()||!evidence.legacySchedulersDisabled())throw new IllegalStateException("Fresh reviewed worker scope required");
        return ops.withOps(actor,()->{
            for(UUID source:sources){String contract=jdbc.queryForObject("select a.contract_key from collection_source_assignment a join data_source s on s.source_id=a.source_id and s.collection_assignment_epoch=a.epoch where s.source_id=?",String.class,source);if(assurance.mode()==CollectionOperationMode.PRODUCTION&&!new CollectionOfficialAdmissionCatalog().productionAdmissible(contract))throw new IllegalStateException("Official contract remains blocked");}
            byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(random);Arrays.fill(random,(byte)0);UUID id=UUID.randomUUID();
            jdbc.queryForList("select collection_production.issue_worker(?,?,?,?,?,?)",actor.memberId(),actor.sessionId(),id,sources.toArray(UUID[]::new),CollectionAssurance.hash(secret),Timestamp.from(until));return new ProductionCollectionWriteGateway.Permit(id,secret);
        });
    }
}
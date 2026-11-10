package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.collection.operations.CollectionAssurance.*;
import com.disasterinfo.eodiisseo.collection.execution.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

/** Signed current metadata -> existing Port. No Bean/default approval/credentials or remote I/O. */
public final class VerifiedCollectionOperationalEvidence implements CollectionOperationalEvidencePort {
    public record SenderBinding(Transport transport,String implementation,String runtime,String endpoint,boolean synthetic){}
    private final JdbcCollectionAssurance store;private final CollectionFleetEvidencePort fleet;
    private final SenderBinding sender;private final Set<String> secrets;private final Clock clock;private final CollectionRawPrivacyPort privacy;
    public VerifiedCollectionOperationalEvidence(JdbcCollectionAssurance store,CollectionFleetEvidencePort fleet,SenderBinding sender,Set<String> knownSecrets,Clock clock){this(store,fleet,sender,knownSecrets,clock,null);
    }
    public VerifiedCollectionOperationalEvidence(JdbcCollectionAssurance store,CollectionFleetEvidencePort fleet,SenderBinding sender,Set<String> knownSecrets,Clock clock,CollectionRawPrivacyPort privacy){this.store=store;this.fleet=fleet;this.sender=sender;this.secrets=Set.copyOf(knownSecrets);this.clock=clock;this.privacy=privacy;}
    private Optional<Artifact> artifact(CollectionAssurance.Kind k,String scope){return store.active(k,scope);}
    private boolean audit(){return store.mode()==CollectionOperationMode.DISPOSABLE_AUDIT;}
    @Override public Map<CollectionOperationalEvidencePort.Kind,Evidence> current(){
        var result=new EnumMap<CollectionOperationalEvidencePort.Kind,Evidence>(CollectionOperationalEvidencePort.Kind.class);
        Map<CollectionOperationalEvidencePort.Kind,CollectionAssurance.Kind> kinds=Map.of(CollectionOperationalEvidencePort.Kind.LEGACY_FLEET,CollectionAssurance.Kind.FLEET,CollectionOperationalEvidencePort.Kind.HTTP_SENDER,CollectionAssurance.Kind.SENDER,CollectionOperationalEvidencePort.Kind.RAW_POLICY,CollectionAssurance.Kind.ARCHIVE,CollectionOperationalEvidencePort.Kind.DOMAIN_POLICY,CollectionAssurance.Kind.DOMAIN_POLICY);
        kinds.forEach((out,in)->artifact(in,"GLOBAL").ifPresent(a->result.put(out,new Evidence(out,a.version(),a.digest(),a.reference(),a.until(),true,audit()||a.synthetic()))));return Map.copyOf(result);
    }
    private boolean fleetReady(){
        var a=artifact(CollectionAssurance.Kind.FLEET,"GLOBAL").orElse(null);var o=fleet==null?null:fleet.current().orElse(null);Instant now=clock.instant();
        if(a==null||o==null||o.instances().isEmpty()||!o.completeInventory()||!o.allStopped()||!o.restartBlocked()||!o.schedulersDisabled()||o.observedAt().isAfter(now)||!o.validUntil().isAfter(now)||(!audit()&&o.synthetic()))return false;
        var p=a.payload();if(!o.instances().equals(new HashSet<>((List<String>)p.get("instance_ids")))||o.observedAt().plusSeconds(((Number)p.get("observation_ttl_seconds")).longValue()).isBefore(now))return false;return Boolean.TRUE.equals(p.get("complete_inventory"))&&Boolean.TRUE.equals(p.get("stopped"))&&Boolean.TRUE.equals(p.get("restart_blocked"))&&Boolean.TRUE.equals(p.get("schedulers_disabled"))
            &&o.inventoryHash().equals(p.get("inventory_sha256"))&&o.deploymentHash().equals(p.get("deployment_sha256"))&&o.restartPolicyHash().equals(p.get("restart_policy_sha256"))&&o.pendingRequestsHash().equals(p.get("pending_requests_sha256"));
    }
    @Override public boolean legacyWorkersStopped(){return fleetReady();}@Override public boolean legacySchedulersDisabled(){return fleetReady();}
    @Override public boolean completionVerified(UUID run,String snapshot,String reference){return artifact(CollectionAssurance.Kind.HTTP_COMPLETION,run.toString().toUpperCase(Locale.ROOT)).filter(a->a.reference().equals(reference)&&Boolean.TRUE.equals(a.payload().get("no_active_request"))&&run.toString().equals(a.payload().get("run_id"))&&snapshot.equals(a.payload().get("snapshot_sha256"))).isPresent()&&fleetReady();}
    @Override public Transport transport(){
        var a=artifact(CollectionAssurance.Kind.SENDER,"GLOBAL").orElse(null);if(a==null||sender==null||sender.transport()==null||(!audit()&&sender.synthetic()))return null;var p=a.payload();
        if(!sender.implementation().equals(p.get("implementation_sha256"))||!sender.runtime().equals(p.get("runtime_sha256"))||!sender.endpoint().equals(p.get("endpoint_sha256")))return null;
        for(String flag:List.of("one_attempt","no_redirect","no_proxy","no_secret_logging","bounded_body","bounded_timeout"))if(!Boolean.TRUE.equals(p.get(flag)))return null;
        return (contract,request)->{if(!fleetReady()||transportStillApproved(a.id())==false||!collectionContract(contract).boundedRange())throw new IllegalStateException("Operational authority withdrawn");return sender.transport().exchange(contract,request);};
    }
    private boolean transportStillApproved(UUID id){return artifact(CollectionAssurance.Kind.SENDER,"GLOBAL").map(a->a.id().equals(id)).orElse(false);}
    @Override public CollectionArchivePolicy archivePolicy(){if(privacy==null)return null;return artifact(CollectionAssurance.Kind.ARCHIVE,"GLOBAL").filter(a->privacy.fingerprint().equals(a.payload().get("secret_detector_sha256"))&&privacy.reviewFingerprint().equals(a.payload().get("privacy_review_sha256"))).map(a->new CollectionArchivePolicy(Duration.ofSeconds(((Number)a.payload().get("retention_seconds")).longValue()),a.reference(),secrets,privacy)).orElse(null);}
    @Override public CollectionProviderPort.Evidence collectionContract(String contract){
        if(!audit()&&!new CollectionOfficialAdmissionCatalog().productionAdmissible(contract))return CollectionProviderPort.Evidence.unverified();
        return artifact(CollectionAssurance.Kind.API,contract).map(a->{var p=a.payload();return new CollectionProviderPort.Evidence(Boolean.TRUE.equals(p.get("bounded_range")),Boolean.TRUE.equals(p.get("stable_snapshot")),Boolean.TRUE.equals(p.get("empty_result")),Boolean.TRUE.equals(p.get("incremental")),a.reference());}).orElse(CollectionProviderPort.Evidence.unverified());
    }
    @Override public boolean quotaApproved(String scope,Instant start,Instant end,long limit,int interval,String reference){
        var a=artifact(CollectionAssurance.Kind.QUOTA,"QUOTA:"+CollectionAssurance.hash(scope).toUpperCase(Locale.ROOT)).orElse(null);if(a==null)return false;var p=a.payload();Instant now=clock.instant();
        return scope.equals(p.get("quota_scope_id"))&&reference.equals(a.reference())&&start.equals(Instant.parse((String)p.get("starts_at")))&&end.equals(Instant.parse((String)p.get("ends_at")))
            &&!now.isBefore(start)&&now.isBefore(end)&&limit==((Number)p.get("request_limit")).longValue()&&interval==((Number)p.get("min_interval_ms")).intValue();
    }
    @Override public List<String> productionBlockers(JdbcTemplate jdbc,Collection<UUID> sources){
        var reasons=new ArrayList<String>();if(audit())reasons.add("AUDIT_ASSURANCE_ADAPTER_NOT_PRODUCTION");if(!fleetReady())reasons.add("FRESH_COMPLETE_FLEET_EXCLUSION_REQUIRED");
        var database=artifact(CollectionAssurance.Kind.DATABASE,"GLOBAL").orElse(null);
        if(database==null)reasons.add("SIGNED_DATABASE_ROLE_POLICY_REQUIRED");else{
            var p=database.payload();var result=new CollectionDatabasePrivilegeInspector(jdbc).inspect(new CollectionDatabasePrivilegeInspector.Policy((String)p.get("worker_role"),(String)p.get("ops_role"),(String)p.get("migration_role"),(String)p.get("schema_owner"),"public"));reasons.addAll(result.blockers());
            if(!result.fingerprint().equals(p.get("acl_sha256")))reasons.add("DATABASE_ACL_FINGERPRINT_CHANGED");
            if(!Objects.equals(jdbc.queryForObject("select current_user",String.class),p.get("worker_role"))||!Objects.equals(jdbc.queryForObject("select session_user",String.class),p.get("worker_role")))reasons.add("WRONG_RUNTIME_DATABASE_PRINCIPAL");
        }
        if(transport()==null)reasons.add("SIGNED_SAFE_SENDER_REQUIRED");if(archivePolicy()==null)reasons.add("SIGNED_ARCHIVE_POLICY_REQUIRED");
        reasons.add("PRODUCTION_PRIVILEGED_WRITE_GATEWAY_NOT_IMPLEMENTED");
        reasons.add("PRODUCTION_BARRIER_NOT_INSTALLED_AUDIT_1005_IS_NOT_PRODUCTION");
        reasons.add("ARCHIVE_ERASURE_AND_AUDIT_RETENTION_CONTRACT_REQUIRED");
        for(UUID source:sources){var keys=jdbc.queryForList("select a.contract_key from data_source s join collection_source_assignment a on a.source_id=s.source_id and a.epoch=s.collection_assignment_epoch where s.source_id=?",source);for(var k:keys)reasons.addAll(new CollectionOfficialAdmissionCatalog().productionBlockers((String)k.get("contract_key")));}
        return List.copyOf(new LinkedHashSet<>(reasons));
    }
}
package com.disasterinfo.eodiisseo.collection.operations;
import java.time.*;
import java.util.*;
import com.disasterinfo.eodiisseo.collection.operations.CollectionAssurance.*;
/** Plans only. Existing immutable body/FK contracts are not silently bypassed to implement erasure. */
public final class CollectionArchiveLifecycle {
    public record Plan(boolean policyApproved,boolean deleteAllowed,List<String> blockers,Instant bodyExpiresAt,String evidenceFingerprint) {}
    private final JdbcCollectionAssurance store;
    public CollectionArchiveLifecycle(JdbcCollectionAssurance store){this.store=store;}
    /** Metadata-only: recorded expiry is preserved, not reconstructed from an assumed fetch time. */
    public Plan inspect(org.springframework.jdbc.core.JdbcTemplate jdbc,UUID attempt,Instant now){
        if(!store.usesDataSource(jdbc.getDataSource()))throw new IllegalArgumentException("Archive evidence and metadata must share a DataSource");
        return store.stable(view->{
            var rows=jdbc.queryForList("select expires_at,exists(select 1 from collection_projection_receipt r where r.attempt_id=a.attempt_id) or exists(select 1 from collection_production.archive_hold_audit h where h.attempt_id=a.attempt_id) as referenced from collection_http_archive a where attempt_id=?",attempt);
            if(rows.size()!=1)return new Plan(false,false,List.of("ARCHIVE_NOT_FOUND"),null,null);
            var row=rows.getFirst();var expiry=((java.sql.Timestamp)row.get("expires_at")).toInstant();
            var reasons=new ArrayList<String>();Optional<Artifact> a;
            try{a=view.find(Kind.ARCHIVE,"GLOBAL");}catch(RuntimeException untrusted){a=Optional.empty();reasons.add("ARCHIVE_POLICY_VERIFICATION_FAILED");}
            if(a.isEmpty())reasons.add("ARCHIVE_RETENTION_ACL_APPROVAL_REQUIRED");
            if(now.isBefore(expiry))reasons.add("RETENTION_NOT_EXPIRED");
            if((boolean)row.get("referenced"))reasons.add("RECEIPT_METADATA_AND_FK_MUST_BE_RETAINED");
            reasons.add("IMMUTABLE_BODY_SPLIT_OR_ERASURE_PROTOCOL_REQUIRED");reasons.add("EXISTING_DATA_MUTATION_NOT_AUTHORIZED");
            return new Plan(a.isPresent(),false,List.copyOf(reasons),expiry,a.map(Artifact::digest).orElse(null));
        });
    }
    public Plan plan(Instant fetchedAt,Instant now,boolean linkedReceipt,boolean immutableBodyContract){
        var artifact=store.active(Kind.ARCHIVE,"GLOBAL");
        if(artifact.isEmpty())return new Plan(false,false,List.of("ARCHIVE_RETENTION_ACL_APPROVAL_REQUIRED"),null,null);
        var a=artifact.get();if(a.kind()!=Kind.ARCHIVE||a.from().isAfter(now)||!a.until().isAfter(now))return new Plan(false,false,List.of("ARCHIVE_POLICY_EXPIRED"),null,null);
        var p=a.payload();long seconds=((Number)p.get("retention_seconds")).longValue();Instant expires;
        try{expires=fetchedAt.plusSeconds(seconds);}catch(RuntimeException invalid){return new Plan(false,false,List.of("RETENTION_RANGE_INVALID"),null,a.digest());}
        var reasons=new ArrayList<String>();if(now.isBefore(expires))reasons.add("RETENTION_NOT_EXPIRED");
        if(immutableBodyContract)reasons.add("IMMUTABLE_BODY_SPLIT_OR_ERASURE_PROTOCOL_REQUIRED");
        if(linkedReceipt)reasons.add("RECEIPT_METADATA_AND_FK_MUST_BE_RETAINED");
        // This stage never authorizes deletion of existing archive bytes or audit records.
        reasons.add("EXISTING_DATA_MUTATION_NOT_AUTHORIZED");return new Plan(true,false,List.copyOf(reasons),expires,a.digest());
    }
}
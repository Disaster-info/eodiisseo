package com.disasterinfo.eodiisseo.collection.operations;
import java.time.*;
import java.util.*;
import com.disasterinfo.eodiisseo.collection.operations.CollectionAssurance.*;
/** Plans only. Existing immutable body/FK contracts are not silently bypassed to implement erasure. */
public final class CollectionArchiveLifecycle {
    public record Plan(boolean policyApproved,boolean deleteAllowed,List<String> blockers,Instant bodyExpiresAt,String evidenceFingerprint) {}
    private final JdbcCollectionAssurance store;
    public CollectionArchiveLifecycle(JdbcCollectionAssurance store){this.store=store;}
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
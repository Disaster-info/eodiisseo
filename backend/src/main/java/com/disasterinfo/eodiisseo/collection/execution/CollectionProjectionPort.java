package com.disasterinfo.eodiisseo.collection.execution;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim;
/** Explicit stage-3 adapter only. All methods participate in the caller's final transaction; no remote I/O. */
public interface CollectionProjectionPort {
    record Result(boolean complete,int stored,String reason){
        public Result {if(stored<0||reason==null||!reason.matches("[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("Safe domain outcome required");}
    }
    final class ReviewRequired extends RuntimeException {
        private final String reason;
        public ReviewRequired(String reason){super("Domain review required");if(!reason.matches("[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("Safe reason required");this.reason=reason;}
        public String reason(){return reason;}
    }
    Result projectVerifiedArchive(Claim claim);
    /** Diagnostics only, after the failed domain transaction rolled back, inside a fresh fenced transaction. */
    default void recordRolledBack(Claim claim,String reason) {}
}
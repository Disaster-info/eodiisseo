package com.disasterinfo.eodiisseo.collection.execution;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim;
import java.util.*;

/** Writes only the new journal/archive namespace; never raw current/revision, Cursor or Coverage. */
public interface CollectionExecutionStore {
    enum State { COMPLETE, INCOMPLETE, FAILED, UNKNOWN, QUOTA_BLOCKED, HELD, DISABLED }
    record Attempt(UUID id,Claim claim,int page,int number) {}
    void start(Claim claim,CollectionProviderPort.Request request,CollectionProviderPort.Evidence evidence);
    /** Atomic fencing + quota reservation + committed IN_FLIGHT journal before external I/O. */
    Optional<Attempt> reserve(Claim claim,int page,int number);
    /** Late results may only enter quarantine; never trust or promote them here. */
    void received(Attempt attempt,CollectionProviderPort.Reply reply,CollectionArchivePolicy.Body body,CollectionArchivePolicy policy);
    void unknown(Attempt attempt);
    void decide(Attempt attempt,String decision);
    void validated(Attempt attempt,int total,int itemCount);
    /** Called within the ownership finish transaction on the exact same DataSource. */
    void terminal(Claim claim,State state,String reason);
    default void beginDomain(Claim claim){throw new IllegalStateException("Projection schema/store required");}
    default void domainResult(Claim claim,String phase,int stored,String reason){throw new IllegalStateException("Projection schema/store required");}
}
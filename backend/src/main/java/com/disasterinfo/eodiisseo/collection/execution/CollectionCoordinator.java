package com.disasterinfo.eodiisseo.collection.execution;

import com.disasterinfo.eodiisseo.collection.ownership.*;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.*;
import com.disasterinfo.eodiisseo.collection.execution.CollectionExecutionStore.*;
import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort.InvalidResponse;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Single explicit executor. Never replaces the legacy worker and has no Spring/scheduler wiring. */
public final class CollectionCoordinator {
    public record Policy(boolean enabled,int maxPages,int attemptsPerPage,int requestBudget,Duration timeout,Duration maxRetryWait) {
        public Policy {
            Objects.requireNonNull(timeout);Objects.requireNonNull(maxRetryWait);
            if(maxPages<1||maxPages>100||attemptsPerPage<1||attemptsPerPage>3||requestBudget<1||requestBudget>300
                ||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(15))>0
                ||maxRetryWait.isNegative()||maxRetryWait.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("Bounded execution policy required");
        }
    }
    public record Result(UUID run,State state,String reason) {}
    private final CollectionOwnership ownership;private final CollectionExecutionStore store;
    private final CollectionProjectionPort projection;
    private final Map<String,CollectionProviderPort> providers;private final Policy policy;private final CollectionArchivePolicy archive;
    public CollectionCoordinator(CollectionOwnership ownership,CollectionExecutionStore store,List<CollectionProviderPort> providers,Policy policy,CollectionArchivePolicy archive){
        this(ownership,store,providers,policy,archive,null);
    }
    /** Explicit domain path. Old HTTP-only constructor/history retain their original meaning. */
    public CollectionCoordinator(CollectionOwnership ownership,CollectionExecutionStore store,List<CollectionProviderPort> providers,Policy policy,CollectionArchivePolicy archive,CollectionProjectionPort projection){
        this.projection=projection;
        this.ownership=Objects.requireNonNull(ownership);this.store=Objects.requireNonNull(store);this.policy=Objects.requireNonNull(policy);this.archive=Objects.requireNonNull(archive);
        var map=new HashMap<String,CollectionProviderPort>();
        for(var p:providers){if(map.putIfAbsent(p.contractKey(),p)!=null)throw new IllegalArgumentException("Duplicate operation plugin");}
        this.providers=Map.copyOf(map);
    }
    public Result collect(UUID source,LocalDate from,LocalDate to,int rows){
        if(!policy.enabled())return new Result(null,State.DISABLED,"EXPLICIT_ACTIVATION_REQUIRED");
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            return new Result(null,State.HELD,"HTTP_REQUIRES_NO_AMBIENT_TRANSACTION");
        var initial=new CollectionProviderPort.Request(from,to,1,rows,policy.timeout());
        Claim claim;
        try{claim=ownership.claim(source,"LIVE");}catch(RuntimeException blocked){return new Result(null,State.HELD,"CLAIM_HELD");}
        var provider=providers.get(claim.assignment().contract());
        try{store.start(claim,initial,provider==null?CollectionProviderPort.Evidence.unverified():provider.evidence());if(projection!=null)store.beginDomain(claim);}catch(RuntimeException unavailable){return new Result(claim.run(),State.HELD,"JOURNAL_NOT_READY");}
        if(provider==null||!provider.executable())return close(claim,State.FAILED,"CONTRACT_REQUIRED");
        try{provider.validateRequest(initial);}catch(RuntimeException invalid){return close(claim,State.FAILED,"INVALID_REQUEST_WINDOW");}
        int total=-1,received=0,calls=0;var identities=new HashMap<String,Map<String,Object>>();
        for(int page=1;page<=policy.maxPages();page++){
            var request=new CollectionProviderPort.Request(from,to,page,rows,policy.timeout());
            for(int attempt=1;attempt<=policy.attemptsPerPage();attempt++){
                if(calls>=policy.requestBudget())return close(claim,State.INCOMPLETE,"REQUEST_BUDGET_EXHAUSTED");
                Attempt journal;
                try{
                    ownership.heartbeat(claim);
                    var reserved=store.reserve(claim,page,attempt);
                    if(reserved.isEmpty())return close(claim,State.QUOTA_BLOCKED,"QUOTA_NOT_AVAILABLE");
                    journal=reserved.orElseThrow();calls++;
                }catch(RuntimeException held){return new Result(claim.run(),State.HELD,"RESERVATION_HELD");}
                var future=new CompletableFuture<CollectionProviderPort.Reply>();
                // No JDBC transaction or row lock crosses this external-call boundary.
                Thread.startVirtualThread(()->{
                    try{
                        var reply=provider.exchange(request);
                        store.received(journal,reply,archive.inspect(reply.body()),archive);
                        future.complete(reply);
                    }catch(Throwable failure){
                        uncertain(journal);future.completeExceptionally(new IllegalStateException("External request or receipt uncertain"));
                    }
                });
                CollectionProviderPort.Reply reply;
                try{reply=future.get(policy.timeout().toNanos(),TimeUnit.NANOSECONDS);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();uncertain(journal);return new Result(claim.run(),State.UNKNOWN,"REQUEST_UNCERTAIN");}
                catch(ExecutionException|TimeoutException uncertain){uncertain(journal);return new Result(claim.run(),State.UNKNOWN,"REQUEST_UNCERTAIN");}
                try{
                    if(archive.inspect(reply.body()).omitted()){
                        store.decide(journal,"REJECTED");return close(claim,State.INCOMPLETE,"SENSITIVE_BODY_OMITTED");
                    }
                    if(reply.status()!=200){
                        boolean retry=(reply.status()==429||reply.status()>=500)&&attempt<policy.attemptsPerPage()
                            &&reply.retryAfter().compareTo(policy.maxRetryWait())<=0;
                        store.decide(journal,retry?"RETRYABLE":"REJECTED");
                        if(!retry)return close(claim,State.FAILED,"HTTP_REJECTED");
                        if(!reply.retryAfter().isZero())TimeUnit.NANOSECONDS.sleep(reply.retryAfter().toNanos());
                        continue;
                    }
                    var decoded=provider.decode(reply,request);
                    if(decoded.number()!=page||decoded.total()<0||decoded.total()>10000||decoded.items().size()>rows
                        ||decoded.items().size()>decoded.total())throw new InvalidResponse("PAGE_TOTAL_INCONSISTENT");
                    if(total<0)total=decoded.total();else if(total!=decoded.total())throw new InvalidResponse("PAGING_CHANGED");
                    if(total>0&&decoded.items().isEmpty())throw new InvalidResponse("PAGE_INCOMPLETE");
                    for(var item:decoded.items()){
                        String key=provider.identity(item);
                        var old=identities.putIfAbsent(key,item);
                        if(old!=null)throw new InvalidResponse(old.equals(item)?"DUPLICATE_RECORD":"CONTENT_CHANGED");
                    }
                    received+=decoded.items().size();
                    if(received>total||(received<total&&decoded.items().size()!=rows))throw new InvalidResponse("PAGE_INCOMPLETE");
                    store.validated(journal,decoded.total(),decoded.items().size());
                    if(received==total){
                        var evidence=provider.evidence();
                        if(total==0&&!evidence.emptyResult())return close(claim,State.INCOMPLETE,"EMPTY_CONTRACT_REQUIRED");
                        if(!evidence.boundedRange())return close(claim,State.INCOMPLETE,"RANGE_CONTRACT_REQUIRED");
                        if(!evidence.stableSnapshot())return close(claim,State.INCOMPLETE,"SNAPSHOT_CONTRACT_REQUIRED");
                        if(!evidence.incremental())return close(claim,State.INCOMPLETE,"INCREMENTAL_CONTRACT_REQUIRED");
                        return close(claim,State.COMPLETE,"COMPLETE_RESPONSE");
                    }
                    break;
                }catch(InvalidResponse invalid){
                    try{store.decide(journal,"REJECTED");}catch(RuntimeException unresolved){uncertain(journal);return new Result(claim.run(),State.UNKNOWN,"REQUEST_UNCERTAIN");}
                    return close(claim,State.INCOMPLETE,safeCode(invalid.code()));
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();return close(claim,State.FAILED,"INTERRUPTED");
                }catch(RuntimeException invalid){
                    try{store.decide(journal,"REJECTED");}catch(RuntimeException unresolved){uncertain(journal);return new Result(claim.run(),State.UNKNOWN,"REQUEST_UNCERTAIN");}
                    return close(claim,State.INCOMPLETE,"VALIDATION_FAILED");
                }
            }
        }
        return close(claim,State.INCOMPLETE,"PAGE_LIMIT");
    }
    private void uncertain(Attempt attempt){try{store.unknown(attempt);}catch(RuntimeException unavailable){/* committed IN_FLIGHT remains blocking; never retry */}}
    private Result close(Claim claim,State state,String reason){
        State[] finalState={state};String[] finalReason={reason};
        try{
            ownership.finishDeciding(claim,()->{
                store.terminal(claim,state,reason);
                if(projection!=null){
                    if(state==State.COMPLETE){
                        var result=projection.projectVerifiedArchive(claim);
                        store.domainResult(claim,result.complete()?"DOMAIN_APPLIED":"DOMAIN_REVIEW",result.stored(),result.reason());
                        if(!result.complete()){finalState[0]=State.FAILED;finalReason[0]=result.reason();}
                    }else store.domainResult(claim,"DOMAIN_FAILED",0,reason);
                }
                return finalState[0]==State.COMPLETE?Outcome.SUCCESS:Outcome.FAILED;
            });
            return new Result(claim.run(),finalState[0],finalReason[0]);
        }catch(RuntimeException failed){
            if(projection!=null&&state==State.COMPLETE){
                String failureReason=failed instanceof CollectionProjectionPort.ReviewRequired r?r.reason():"PROJECTION_ROLLED_BACK";
                try{
                    ownership.finish(claim,Outcome.FAILED,()->{
                        store.terminal(claim,State.FAILED,failureReason);
                        store.domainResult(claim,"DOMAIN_FAILED",0,failureReason);projection.recordRolledBack(claim,failureReason);
                    });
                    return new Result(claim.run(),State.FAILED,failureReason);
                }catch(RuntimeException lost){/* No late state writes after lease/fence loss. */}
            }
            return new Result(claim.run(),State.HELD,"OWNERSHIP_OR_JOURNAL_HELD");
        }
    }
    private static String safeCode(String code){
        return Set.of("SCHEMA","NO_DATA","PAGE_LIMIT","PAGE_INCOMPLETE","PAGE_MISMATCH","PAGE_TOTAL_INCONSISTENT","PAGING_CHANGED",
            "DUPLICATE_RECORD","CONTENT_CHANGED","AUTH_REJECTED","RATE_LIMIT","PROVIDER_CODE","WINDOW_RESPONSE_MISMATCH","ROW_LIMIT").contains(code)?code:"VALIDATION_FAILED";
    }
}
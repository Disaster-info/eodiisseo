package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.collection.ownership.*;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.*;
import java.util.*;
/** Typed heartbeat only. No endpoint, SQL text, user callback, credential or automatic Bean.
 * Real channel authentication and a durable same-transaction journal remain mandatory external bindings. */
public final class CollectionWorkerCommandBoundary {
 public record Heartbeat(UUID command,UUID worker,UUID source,UUID run,UUID claim,long fence,long generation,Instant until,byte[] signature){
  public Heartbeat{Objects.requireNonNull(command);Objects.requireNonNull(worker);Objects.requireNonNull(source);Objects.requireNonNull(run);Objects.requireNonNull(claim);Objects.requireNonNull(until);if(fence<1||generation<1||signature==null||signature.length!=64)throw new IllegalArgumentException("Bounded signed heartbeat required");signature=signature.clone();}
  @Override public byte[] signature(){return signature.clone();}
  @Override public String toString(){return "WorkerHeartbeat[redacted]";}
  public String digest(){return CollectionAssurance.hash(CollectionAssurance.canonical(Map.of("command",command.toString(),"worker",worker.toString(),"source",source.toString(),"run",run.toString(),"claim",claim.toString(),"fence",fence,"generation",generation,"until",until.toString())));}
 }
 public record Authorization(Claim claim,ProductionCollectionWriteGateway.Permit permit,long generation,Instant until){@Override public String toString(){return "GatewayAuthorization[redacted]";}}
 /** Must verify pinned caller identity, signature, exact digest/scope, revocation and generation. No remote I/O. */
 public interface Authorizer {Authorization require(Heartbeat request);}
 /** INSERT command/digest in THIS transaction; conflict with different digest MUST fail. No autonomous commit. */
 public interface Journal {javax.sql.DataSource dataSource();boolean reserve(UUID command,String digest);}
 private final ProductionCollectionWriteGateway gateway;private final CollectionOwnership ownership;private final Authorizer authorizer;private final Journal journal;private final Clock clock;
 public CollectionWorkerCommandBoundary(JdbcTemplate jdbc,JpaTransactionManager manager,ProductionCollectionWriteGateway gateway,Authorizer authorizer,Journal journal,Duration lease,Clock clock){
  this.gateway=Objects.requireNonNull(gateway);this.authorizer=Objects.requireNonNull(authorizer);this.journal=Objects.requireNonNull(journal);this.clock=Objects.requireNonNull(clock);
  if(!gateway.usesDataSource(jdbc.getDataSource())||journal.dataSource()!=jdbc.getDataSource())throw new IllegalArgumentException("Same physical command journal transaction required");
  ownership=new CollectionOwnership(new JdbcCollectionOwnershipStore(jdbc,manager),new CollectionContractRegistry(),lease);
 }
 private Authorization authorized(Heartbeat request){
  var a=Objects.requireNonNull(authorizer.require(request));var c=Objects.requireNonNull(a.claim());
  if(!request.until().isAfter(clock.instant())||!a.until().isAfter(clock.instant())||a.generation()!=request.generation()||!c.run().equals(request.run())||!c.claimId().equals(request.claim())||!c.assignment().source().equals(request.source())||c.fence()!=request.fence())throw new IllegalStateException("Command scope withdrawn or mismatched");return a;
 }
 public boolean heartbeat(Heartbeat request){
  var a=authorized(request);
  return gateway.write(a.permit(),request.source(),ProductionCollectionWriteGateway.Phase.EXECUTION,a.claim(),()->{
   var fresh=authorized(request);if(!fresh.claim().equals(a.claim())||fresh.generation()!=a.generation())throw new IllegalStateException("Command authority changed");
   boolean first=journal.reserve(request.command(),request.digest());if(first)ownership.heartbeat(a.claim());authorized(request);return first;
  });
 }
}

package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
/** Serializes persisted permission changes with DB COMMIT. This is not an external supervisor lease. */
public final class JdbcCollectionFleetCommitFence {
 private final JdbcTemplate jdbc;private final CollectionOperationsService ops;private final JdbcCollectionAssurance assurance;private final VerifiedCollectionOperationalEvidence evidence;
 public JdbcCollectionFleetCommitFence(JdbcTemplate jdbc,CollectionOperationsService ops,JdbcCollectionAssurance assurance,VerifiedCollectionOperationalEvidence evidence){
  this.jdbc=Objects.requireNonNull(jdbc);this.ops=Objects.requireNonNull(ops);this.assurance=Objects.requireNonNull(assurance);this.evidence=Objects.requireNonNull(evidence);
  if(!ops.usesDataSource(jdbc.getDataSource())||!assurance.usesDataSource(jdbc.getDataSource())||!evidence.usesDataSource(jdbc.getDataSource()))throw new IllegalArgumentException("One reviewed OPS transaction required");
 }
 public void publish(MemberPrincipal actor,long expectedEpoch,Instant until,boolean revoked){
  ops.withOps(actor,()->assurance.stable(view->{
   var a=view.find(CollectionAssurance.Kind.FLEET,"GLOBAL").orElseThrow(()->new IllegalStateException("Signed Fleet required"));
   if(until==null||until.isAfter(a.until())||!evidence.legacyWorkersStopped()||!evidence.legacySchedulersDisabled())throw new IllegalStateException("Fresh complete observed Fleet required");
   jdbc.queryForList("select collection_production.publish_fleet_fence(?,?,?,?,?,?,?)",actor.memberId(),actor.sessionId(),expectedEpoch,a.id(),a.digest(),Timestamp.from(until),revoked);
   if(!evidence.legacyWorkersStopped()||!evidence.legacySchedulersDisabled())throw new IllegalStateException("Fleet changed before publication");return null;
  }));
 }
}

package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.util.*;
/** No Bean, no listener and no credential discovery. Public key is approved by OPS in the DB.
 * The permit resolver is Gateway-local; no plaintext capability is received from the Worker. */
public final class JdbcCollectionCommandAuthorizer implements CollectionWorkerCommandBoundary.Authorizer {
 public interface PermitResolver {ProductionCollectionWriteGateway.Permit forWorker(UUID worker);}
 private final JdbcTemplate jdbc;private final CollectionCommandPeerPort peers;private final PermitResolver permits;private final CollectionOperationMode mode;
 public JdbcCollectionCommandAuthorizer(JdbcTemplate jdbc,CollectionCommandPeerPort peers,PermitResolver permits,CollectionOperationMode mode){this.jdbc=Objects.requireNonNull(jdbc);this.peers=Objects.requireNonNull(peers);this.permits=Objects.requireNonNull(permits);this.mode=Objects.requireNonNull(mode);mode.verify(jdbc.getDataSource());}
 public static byte[] signingBytes(CollectionWorkerCommandBoundary.Heartbeat r){return ("EODIISSEO_COLLECTION_HEARTBEAT_V1:"+r.digest()).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 @Override public CollectionWorkerCommandBoundary.Authorization require(CollectionWorkerCommandBoundary.Heartbeat r){
  var peer=Objects.requireNonNull(peers.requireVerifiedPeer());var now=jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();
  if(!peer.worker().equals(r.worker())||!peer.until().isAfter(now)||!r.until().isAfter(now)||(!mode.accepts(peer.synthetic())))throw new IllegalStateException("Command peer denied");
  var rows=jdbc.queryForList("select * from collection_production.command_authority(?,?,?,?,?,?)",r.worker(),r.source(),r.run(),r.claim(),r.fence(),r.generation());
  if(rows.size()!=1)throw new IllegalStateException("Command scope expired or revoked");var row=rows.getFirst();if(!Objects.equals(row.get("audit_only"),mode==CollectionOperationMode.DISPOSABLE_AUDIT))throw new IllegalStateException("Command evidence environment mismatch");var until=((Timestamp)row.get("until_at")).toInstant();
  if(r.until().isAfter(until)||r.until().isAfter(peer.until())||!peer.certificateSha256().equals(row.get("peer_sha256")))throw new IllegalStateException("Command peer scope mismatch");
  try{var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec((byte[])row.get("key_der")));var signature=Signature.getInstance("Ed25519");signature.initVerify(key);signature.update(signingBytes(r));if(!signature.verify(r.signature()))throw new GeneralSecurityException();}catch(GeneralSecurityException rejected){throw new IllegalStateException("Command signature denied");}
  var assignment=new Assignment(r.source(),(String)row.get("source_alias"),(UUID)row.get("target_id"),(String)row.get("contract_key"),(String)row.get("quota_scope_id"),((Number)row.get("assignment_epoch")).longValue(),(String)row.get("execution_owner"),(String)row.get("approval_reference"),(boolean)row.get("quiesced"));
  return new CollectionWorkerCommandBoundary.Authorization(new Claim(r.run(),r.claim(),assignment,"LIVE",r.fence()),Objects.requireNonNull(permits.forWorker(r.worker())),r.generation(),until);
 }
}

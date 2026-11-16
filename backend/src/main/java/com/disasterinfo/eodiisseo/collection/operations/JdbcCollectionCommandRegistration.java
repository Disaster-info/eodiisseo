package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
/** Explicit OPS registration, no automatic keys/peer approval and no private key persistence. */
public final class JdbcCollectionCommandRegistration {
 private final JdbcTemplate jdbc;private final CollectionOperationsService ops;
 public JdbcCollectionCommandRegistration(JdbcTemplate jdbc,CollectionOperationsService ops){this.jdbc=Objects.requireNonNull(jdbc);this.ops=Objects.requireNonNull(ops);if(!ops.usesDataSource(jdbc.getDataSource()))throw new IllegalArgumentException("Same OPS DataSource required");}
 public void register(MemberPrincipal actor,UUID worker,PublicKey key,String peer,Instant until){
  if(!Set.of("Ed25519","EdDSA").contains(key.getAlgorithm())||peer==null||!peer.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Reviewed Ed25519/peer binding required");
  ops.withOps(actor,()->{jdbc.queryForList("select collection_production.register_command_key(?,?,?,?,?,?)",actor.memberId(),actor.sessionId(),worker,key.getEncoded(),peer,Timestamp.from(until));return null;});
 }
 public void revoke(MemberPrincipal actor,UUID worker){ops.withOps(actor,()->{jdbc.queryForList("select collection_production.revoke_command_key(?,?,?)",actor.memberId(),actor.sessionId(),worker);return null;});}
}

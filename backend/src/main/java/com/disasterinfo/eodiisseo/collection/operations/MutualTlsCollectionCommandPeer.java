package com.disasterinfo.eodiisseo.collection.operations;
import javax.net.ssl.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
/** Bind THIS successfully verified client-authenticated TLS session to an OPS-approved certificate.
 * Caller must supply the real listener session with needClientAuth and reviewed trust managers.
 * No listener/default trust or worker identity is taken from request headers. */
public final class MutualTlsCollectionCommandPeer implements CollectionCommandPeerPort {
 private final JdbcTemplate jdbc;private final java.util.function.Supplier<SSLSession> session;
 public MutualTlsCollectionCommandPeer(JdbcTemplate jdbc,java.util.function.Supplier<SSLSession> session){this.jdbc=Objects.requireNonNull(jdbc);this.session=Objects.requireNonNull(session);}
 @Override public Peer requireVerifiedPeer(){
  try{
   var s=Objects.requireNonNull(session.get());if(!s.isValid()||!Set.of("TLSv1.2","TLSv1.3").contains(s.getProtocol())||s.getPeerPrincipal()==null)throw new GeneralSecurityException();
   var certs=s.getPeerCertificates();if(certs.length==0||!(certs[0] instanceof X509Certificate leaf))throw new GeneralSecurityException();leaf.checkValidity();
   String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));var rows=jdbc.queryForList("select * from collection_production.command_peer(?)",fingerprint);if(rows.size()!=1)throw new GeneralSecurityException();var r=rows.getFirst();
   var until=((Timestamp)r.get("until_at")).toInstant();if(leaf.getNotAfter().toInstant().isBefore(until))until=leaf.getNotAfter().toInstant();
   return new Peer((UUID)r.get("worker_id"),fingerprint,until,(boolean)r.get("audit_only"));
  }catch(Exception rejected){throw new IllegalStateException("Verified mutual TLS command peer required");}
 }
}

package com.disasterinfo.eodiisseo.disaster.projection;
import com.disasterinfo.eodiisseo.collection.operations.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.*;
import java.security.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Timeout(90)
class CollectionCommandFleetPostgresTest {
 final CollectionProductionLoginPostgresTest f=new CollectionProductionLoginPostgresTest();
 com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim claim;KeyPair key;long generation;Instant until;final String peer="b".repeat(64);
 @BeforeAll void open(){f.open();
  for(var row:f.f.sql.queryForList("select oid::regprocedure::text as signature,proname from pg_proc where pronamespace='collection_production'::regnamespace")){
   String name=(String)row.get("proname"),signature=(String)row.get("signature");
   if(Set.of("command_peer","command_authority","reserve_command","complete_command","record_command_failure").contains(name))f.f.sql.execute("grant execute on function "+signature+" to "+f.gateway);
   if(Set.of("register_command_key","revoke_command_key","publish_fleet_fence").contains(name))f.f.sql.execute("grant execute on function "+signature+" to "+f.operator);
  }
 }
 @BeforeEach void fixture()throws Exception{
  f.f.sql.execute("truncate collection_production.fleet_fence,collection_production.fleet_fence_history,collection_production.fleet_context cascade");
  f.fixture();f.ready();claim=f.claim();key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();generation=f.f.sql.queryForObject("select generation from collection_production.barrier",Long.class);until=Instant.now().plusSeconds(75);
  f.ops.queryForList("select collection_production.register_command_key(?,?,?,?,?,?)",f.f.ops.memberId(),f.f.ops.sessionId(),f.permit,key.getPublic().getEncoded(),peer,Timestamp.from(until));
 }
 @AfterAll void close(){f.close();}
 CollectionWorkerCommandBoundary.Heartbeat request(UUID id)throws Exception{
  var r=new CollectionWorkerCommandBoundary.Heartbeat(id,f.permit,f.f.source,claim.run(),claim.claimId(),claim.fence(),generation,Instant.now().plusSeconds(45),new byte[64]);return sign(r);
 }
 CollectionWorkerCommandBoundary.Heartbeat sign(CollectionWorkerCommandBoundary.Heartbeat r)throws Exception{
  var s=Signature.getInstance("Ed25519");s.initSign(key.getPrivate());s.update(JdbcCollectionCommandAuthorizer.signingBytes(r));return new CollectionWorkerCommandBoundary.Heartbeat(r.command(),r.worker(),r.source(),r.run(),r.claim(),r.fence(),r.generation(),r.until(),s.sign());
 }
 CollectionWorkerCommandBoundary boundary(){
  var auth=new JdbcCollectionCommandAuthorizer(f.gate,()->new CollectionCommandPeerPort.Peer(f.permit,peer,until,true),w->f.handle(),CollectionOperationMode.DISPOSABLE_AUDIT);
  return new CollectionWorkerCommandBoundary(f.gate,f.gatewayApp.getBean(JpaTransactionManager.class),f.writes,auth,new JdbcCollectionCommandJournal(f.gate,f.gatewayApp.getBean(JpaTransactionManager.class),f.permit),Duration.ofMinutes(1),Clock.systemUTC());
 }
 long count(String table){return f.count("collection_production."+table);}
 @Test void signedHeartbeatIsDurableAndDuplicateAfterServiceRestartDoesNotRenewAgain()throws Exception{
  var r=request(UUID.randomUUID());assertTrue(boundary().heartbeat(r));var lease=f.f.sql.queryForObject("select lease_until from collection_target_lease where run_id=?",Timestamp.class,claim.run());
  assertFalse(boundary().heartbeat(r));assertEquals(lease,f.f.sql.queryForObject("select lease_until from collection_target_lease where run_id=?",Timestamp.class,claim.run()));assertEquals(1,count("command_receipt"));assertEquals("COMPLETED",f.f.sql.queryForObject("select state from collection_production.command_receipt",String.class));
  assertThrows(RuntimeException.class,()->f.gate.update("delete from collection_production.command_receipt"));assertThrows(RuntimeException.class,()->f.f.sql.update("delete from collection_production.command_receipt"));
 }
 @Test void duplicateAfterActualGatewayJvmRestartUsesDurableReceipt()throws Exception{
  var r=request(UUID.randomUUID());assertTrue(boundary().heartbeat(r));var lease=f.f.sql.queryForObject("select lease_until from collection_target_lease where run_id=?",Timestamp.class,claim.run());
  var cp=new LinkedHashSet<String>();for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent())if(loader instanceof java.net.URLClassLoader urls)for(var uri:urls.getURLs())if(uri.getProtocol().equals("file"))cp.add(java.nio.file.Path.of(uri.toURI()).toString());cp.add(System.getProperty("java.class.path"));
  var args=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Dlogback.configurationFile="+Objects.requireNonNull(getClass().getResource("/collection4c3/child-logback.xml")).toExternalForm(),"-cp",String.join(java.io.File.pathSeparator,cp),CollectionGatewayReplayProbeMain.class.getName(),"jdbc:postgresql://127.0.0.1:55433/"+f.f.database,f.gateway);
  var systemRoot=System.getenv("SystemRoot");args.environment().clear();if(systemRoot!=null)args.environment().put("SystemRoot",systemRoot);args.redirectError(ProcessBuilder.Redirect.DISCARD);
  var child=args.start();var output=CompletableFuture.supplyAsync(()->{String result="";try(var reader=child.inputReader()){String line;while((line=reader.readLine())!=null)if(line.startsWith("REPLAY_")&&line.length()<64)result=line;}catch(java.io.IOException e){return "";}return result;});
  try{
   assertNotEquals(ProcessHandle.current().pid(),child.pid());var payload=new HashMap<String,Object>();payload.put("password",new String(f.secrets.get(f.gateway)));payload.put("capability",f.capability);payload.put("worker",r.worker().toString());payload.put("command",r.command().toString());payload.put("source",r.source().toString());payload.put("run",r.run().toString());payload.put("claim",r.claim().toString());payload.put("fence",r.fence());payload.put("generation",r.generation());payload.put("until",r.until().toString());payload.put("signature",Base64.getEncoder().encodeToString(r.signature()));payload.put("peer",peer);
   try(var writer=child.outputWriter()){writer.write(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(payload));writer.newLine();}payload.clear();
   assertTrue(child.waitFor(30,TimeUnit.SECONDS));assertEquals(0,child.exitValue());assertEquals("REPLAY_DUPLICATE",output.get(3,TimeUnit.SECONDS));assertEquals(1,count("command_receipt"));assertEquals(lease,f.f.sql.queryForObject("select lease_until from collection_target_lease where run_id=?",Timestamp.class,claim.run()));
  }finally{if(child.isAlive())child.destroyForcibly();}
 }
 @Test void badSignatureScopePeerAndGenerationNeverCreateReceipts()throws Exception{
  var r=request(UUID.randomUUID());var corrupt=r.signature();corrupt[0]^=1;
  assertThrows(RuntimeException.class,()->boundary().heartbeat(new CollectionWorkerCommandBoundary.Heartbeat(r.command(),r.worker(),r.source(),r.run(),r.claim(),r.fence(),r.generation(),r.until(),corrupt)));
  var other=sign(new CollectionWorkerCommandBoundary.Heartbeat(r.command(),r.worker(),UUID.randomUUID(),r.run(),r.claim(),r.fence(),r.generation(),r.until(),new byte[64]));assertThrows(RuntimeException.class,()->boundary().heartbeat(other));
  var old=sign(new CollectionWorkerCommandBoundary.Heartbeat(r.command(),r.worker(),r.source(),r.run(),r.claim(),r.fence()+1,r.generation(),r.until(),new byte[64]));assertThrows(RuntimeException.class,()->boundary().heartbeat(old));
  var wrongPeer=new JdbcCollectionCommandAuthorizer(f.gate,()->new CollectionCommandPeerPort.Peer(f.permit,"c".repeat(64),until,true),w->f.handle(),CollectionOperationMode.DISPOSABLE_AUDIT);assertThrows(RuntimeException.class,()->wrongPeer.require(r));
  var relabeled=new JdbcCollectionCommandAuthorizer(f.gate,()->new CollectionCommandPeerPort.Peer(f.permit,peer,until,false),w->f.handle(),CollectionOperationMode.PRODUCTION);assertThrows(RuntimeException.class,()->relabeled.require(r));assertEquals(0,count("command_receipt"));
 }
 @Test void revokedKeyAndExpiredCommandAreRejected()throws Exception{
  var r=request(UUID.randomUUID());var expired=sign(new CollectionWorkerCommandBoundary.Heartbeat(r.command(),r.worker(),r.source(),r.run(),r.claim(),r.fence(),r.generation(),Instant.now().minusSeconds(1),new byte[64]));assertThrows(RuntimeException.class,()->boundary().heartbeat(expired));
  f.ops.queryForList("select collection_production.revoke_command_key(?,?,?)",f.f.ops.memberId(),f.f.ops.sessionId(),f.permit);assertThrows(RuntimeException.class,()->boundary().heartbeat(r));assertEquals(0,count("command_receipt"));
 }
 @Test void sameIdDifferentDigestIsRejected()throws Exception{var r=request(UUID.randomUUID());assertTrue(boundary().heartbeat(r));Thread.sleep(2);var changed=sign(new CollectionWorkerCommandBoundary.Heartbeat(r.command(),r.worker(),r.source(),r.run(),r.claim(),r.fence(),r.generation(),r.until().minusSeconds(1),new byte[64]));assertThrows(RuntimeException.class,()->boundary().heartbeat(changed));assertEquals(1,count("command_receipt"));assertEquals(1,count("command_failure"));}
 @Test void rollbackDoesNotConsumeRequestAndFailureAuditSurvives()throws Exception{
  var r=request(UUID.randomUUID());f.f.sql.execute("create function command_failure_fixture() returns trigger language plpgsql as $$ begin raise exception 'SYNTHETIC'; end $$");f.f.sql.execute("create trigger command_failure_fixture before update on collection_job_lock for each row execute function command_failure_fixture()");
  try{assertThrows(RuntimeException.class,()->boundary().heartbeat(r));assertEquals(0,count("command_receipt"));assertEquals(1,count("command_failure"));}finally{f.f.sql.execute("drop trigger command_failure_fixture on collection_job_lock");f.f.sql.execute("drop function command_failure_fixture()");}
  assertTrue(boundary().heartbeat(r));assertEquals(1,count("command_receipt"));
 }
 void publish(long expected,Instant expiry,boolean revoke){f.ops.queryForList("select collection_production.publish_fleet_fence(?,?,?,?,?,?,?)",f.f.ops.memberId(),f.f.ops.sessionId(),expected,f.artifacts.getFirst(),"a".repeat(64),Timestamp.from(expiry),revoke);}
 @Test void fleetEpochRevocationSerializesWithCommitAndRejectsOldWorker()throws Exception{
  publish(0,Instant.now().plusSeconds(60),false);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var write=pool.submit(()->f.writes.write(f.handle(),f.f.source,ProductionCollectionWriteGateway.Phase.EXECUTION,claim,()->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){throw new IllegalStateException(e);}return true;}));
   assertTrue(entered.await(5,TimeUnit.SECONDS));var revoke=pool.submit(()->{publish(1,Instant.now().plusSeconds(60),true);return true;});
   try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waits=false;while(System.nanoTime()<deadline){if(f.f.sql.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",Long.class)>0){waits=true;break;}Thread.sleep(10);}assertTrue(waits);assertFalse(revoke.isDone());}finally{release.countDown();}
   assertTrue(write.get(10,TimeUnit.SECONDS));assertTrue(revoke.get(10,TimeUnit.SECONDS));
  }
  assertThrows(RuntimeException.class,()->boundary().heartbeat(request(UUID.randomUUID())));assertEquals(2,f.f.sql.queryForObject("select epoch from collection_production.fleet_fence",Long.class));assertEquals(2,count("fleet_fence_history"));
 }
 @Test void fleetExpiryAfterExplicitCloseFailsAtCommit()throws Exception{
  Instant expiry=f.f.sql.queryForObject("select clock_timestamp()+interval '10 seconds'",Timestamp.class).toInstant();publish(0,expiry,false);
  var tx=new org.springframework.transaction.support.TransactionTemplate(f.gatewayApp.getBean(JpaTransactionManager.class));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);var closed=new java.util.concurrent.atomic.AtomicBoolean();
  assertThrows(RuntimeException.class,()->tx.execute(s->{f.gate.queryForList("select collection_production.open_context(?,?,?,'EXECUTION',?,?,?)",f.permit,f.capability,f.f.source,claim.run(),claim.claimId(),claim.fence());f.gate.update("insert into projection_tx_probe values(?)",UUID.randomUUID());f.gate.queryForList("select collection_production.close_context()");closed.set(true);f.gate.queryForList("select pg_sleep(greatest(0.0,extract(epoch from (?::timestamptz-clock_timestamp())))+0.1)",Timestamp.from(expiry));return null;}));
  assertTrue(closed.get());assertEquals(0,f.count("projection_tx_probe"));
 }
 @Test @EnabledIfEnvironmentVariable(named="EODIISSEO_GATEWAY_TLS_TESTS",matches="true")
 void actualMutualTlsPeerAndSignedBoundedHeartbeatAreBoundTogether()throws Exception{
  var root=java.nio.file.Path.of(Objects.requireNonNull(System.getenv("EODIISSEO_GATEWAY_TLS_ROOT"))).toRealPath();
  assertTrue(root.startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toRealPath()));assertTrue(root.getFileName().toString().matches("c4c4-gateway-tls-[a-f0-9]{32}"));
  char[] password=java.nio.file.Files.readString(root.resolve("bootstrap.password")).toCharArray();
  try{
   var serverContext=tlsContext(root,"command-server.p12",password);var clientContext=tlsContext(root,"command-client.p12",password);
   var ks=java.security.KeyStore.getInstance("PKCS12");try(var input=java.nio.file.Files.newInputStream(root.resolve("command-client.p12"))){ks.load(input,password);}
   var cert=ks.getCertificate(ks.aliases().nextElement());String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
   UUID tlsWorker=UUID.randomUUID();String capability=UUID.randomUUID().toString()+UUID.randomUUID();
   f.ops.queryForList("select collection_production.issue_worker(?,?,?,?,?,?)",f.f.ops.memberId(),f.f.ops.sessionId(),tlsWorker,new UUID[]{f.f.source},CollectionAssurance.hash(capability),Timestamp.from(until));
   f.ops.queryForList("select collection_production.register_command_key(?,?,?,?,?,?)",f.f.ops.memberId(),f.f.ops.sessionId(),tlsWorker,key.getPublic().getEncoded(),fingerprint,Timestamp.from(until));
   var r=sign(new CollectionWorkerCommandBoundary.Heartbeat(UUID.randomUUID(),tlsWorker,f.f.source,claim.run(),claim.claimId(),claim.fence(),generation,Instant.now().plusSeconds(40),new byte[64]));
   try(var server=(javax.net.ssl.SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.2"));var pool=Executors.newVirtualThreadPerTaskExecutor()){
    server.setNeedClientAuth(true);server.setSoTimeout(5000);
    var handled=pool.submit(()->{try(var socket=(javax.net.ssl.SSLSocket)server.accept()){
     socket.setSoTimeout(5000);socket.startHandshake();var input=new java.io.DataInputStream(socket.getInputStream());int size=input.readInt();if(size<1||size>CollectionHeartbeatWireCodec.MAX_BYTES)throw new IllegalArgumentException();byte[] bytes=input.readNBytes(size);if(bytes.length!=size)throw new IllegalArgumentException();
     var peerBinding=new MutualTlsCollectionCommandPeer(f.gate,socket::getSession);assertEquals(tlsWorker,peerBinding.requireVerifiedPeer().worker());
     var auth=new JdbcCollectionCommandAuthorizer(f.gate,peerBinding,w->{assertEquals(tlsWorker,w);return new ProductionCollectionWriteGateway.Permit(tlsWorker,capability);},CollectionOperationMode.DISPOSABLE_AUDIT);
     var boundary=new CollectionWorkerCommandBoundary(f.gate,f.gatewayApp.getBean(JpaTransactionManager.class),f.writes,auth,new JdbcCollectionCommandJournal(f.gate,f.gatewayApp.getBean(JpaTransactionManager.class),tlsWorker),Duration.ofMinutes(1),Clock.systemUTC());
     boolean result=boundary.heartbeat(CollectionHeartbeatWireCodec.decode(bytes));new java.io.DataOutputStream(socket.getOutputStream()).writeBoolean(result);return result;
    }});
    try(var client=(javax.net.ssl.SSLSocket)clientContext.getSocketFactory().createSocket("127.0.0.2",server.getLocalPort())){
     client.setSoTimeout(10000);var parameters=client.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");client.setSSLParameters(parameters);client.startHandshake();byte[] bytes=CollectionHeartbeatWireCodec.encode(r);var output=new java.io.DataOutputStream(client.getOutputStream());output.writeInt(bytes.length);output.write(bytes);output.flush();assertTrue(new java.io.DataInputStream(client.getInputStream()).readBoolean());
    }
    assertTrue(handled.get(10,TimeUnit.SECONDS));assertEquals(1,count("command_receipt"));
   }
   // Certificate lookup after explicit OPS key revocation is rejected, independent of signature validity.
   f.ops.queryForList("select collection_production.revoke_command_key(?,?,?)",f.f.ops.memberId(),f.f.ops.sessionId(),tlsWorker);assertTrue(f.gate.queryForList("select * from collection_production.command_peer(?)",fingerprint).isEmpty());
  }finally{Arrays.fill(password,'\0');}
 }
 static javax.net.ssl.SSLContext tlsContext(java.nio.file.Path root,String identity,char[] password)throws Exception{
  var keys=java.security.KeyStore.getInstance("PKCS12");try(var input=java.nio.file.Files.newInputStream(root.resolve(identity))){keys.load(input,password);}var km=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password);
  var trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());trust.load(null,null);try(var input=java.nio.file.Files.newInputStream(root.resolve("ca.crt"))){trust.setCertificateEntry("only-owned-ca",java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input));}var tm=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);var context=javax.net.ssl.SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),tm.getTrustManagers(),null);return context;
 }

 @Test void commandKeyExpiryBetweenCloseAndActualCommitRollsBackReceipt()throws Exception{
  Instant expiry=f.f.sql.queryForObject("select clock_timestamp()+interval '3 seconds'",Timestamp.class).toInstant();f.f.sql.update("update collection_production.command_key set valid_until=? where worker_id=?",Timestamp.from(expiry),f.permit);
  var r=request(UUID.randomUUID());var tx=new org.springframework.transaction.support.TransactionTemplate(f.gatewayApp.getBean(JpaTransactionManager.class));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);var closed=new java.util.concurrent.atomic.AtomicBoolean();
  assertThrows(RuntimeException.class,()->tx.execute(s->{f.gate.queryForList("select collection_production.open_context(?,?,?,'EXECUTION',?,?,?)",f.permit,f.capability,f.f.source,claim.run(),claim.claimId(),claim.fence());f.gate.queryForObject("select collection_production.reserve_command(?,?,?)",Boolean.class,r.command(),r.digest(),Timestamp.from(r.until()));f.gate.queryForList("select collection_production.complete_command(?,?)",r.command(),r.digest());f.gate.queryForList("select collection_production.close_context()");closed.set(true);f.gate.queryForList("select pg_sleep(greatest(0.0,extract(epoch from (?::timestamptz-clock_timestamp())))+0.1)",Timestamp.from(expiry));return null;}));assertTrue(closed.get());assertEquals(0,count("command_receipt"));
 }

 @Test void productionInstallationCannotCreateContextWithoutPersistedFleetFence(){
  f.f.sql.update("update collection_production.installation set audit_only=false");
  try{var failure=assertThrows(RuntimeException.class,()->f.f.sql.update("insert into collection_production.context(transaction_id,backend_pid,worker_id,source_id,phase) values(pg_current_xact_id(),pg_backend_pid(),?,?,'EXECUTION')",f.permit,f.f.source));assertTrue(rootMessage(failure).contains("Fleet fence missing"));}
  finally{f.f.sql.update("update collection_production.installation set audit_only=true");}
 }
 @Test void lostFenceRowCannotResetDurableEpochOrReuseOldAuthority(){
  publish(0,Instant.now().plusSeconds(60),false);f.f.sql.execute("delete from collection_production.fleet_fence");
  assertThrows(RuntimeException.class,()->f.writes.write(f.handle(),f.f.source,ProductionCollectionWriteGateway.Phase.EXECUTION,claim,()->true));assertThrows(RuntimeException.class,()->publish(0,Instant.now().plusSeconds(60),false));assertEquals(1,count("fleet_fence_history"));
 }
 static String rootMessage(Throwable t){while(t.getCause()!=null)t=t.getCause();return Objects.toString(t.getMessage(),"");}

 @Test void signedRequestExpiryBeforeActualCommitRollsBackCompletedReceipt()throws Exception{
  Instant expiry=f.f.sql.queryForObject("select clock_timestamp()+interval '3 seconds'",Timestamp.class).toInstant();var r=request(UUID.randomUUID());var tx=new org.springframework.transaction.support.TransactionTemplate(f.gatewayApp.getBean(JpaTransactionManager.class));tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);var closed=new java.util.concurrent.atomic.AtomicBoolean();
  assertThrows(RuntimeException.class,()->tx.execute(s->{f.gate.queryForList("select collection_production.open_context(?,?,?,'EXECUTION',?,?,?)",f.permit,f.capability,f.f.source,claim.run(),claim.claimId(),claim.fence());f.gate.queryForObject("select collection_production.reserve_command(?,?,?)",Boolean.class,r.command(),r.digest(),Timestamp.from(expiry));f.gate.queryForList("select collection_production.complete_command(?,?)",r.command(),r.digest());f.gate.queryForList("select collection_production.close_context()");closed.set(true);f.gate.queryForList("select pg_sleep(greatest(0.0,extract(epoch from (?::timestamptz-clock_timestamp())))+0.1)",Timestamp.from(expiry));return null;}));assertTrue(closed.get());assertEquals(0,count("command_receipt"));
 }

}

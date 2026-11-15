package com.disasterinfo.eodiisseo.disaster.projection;
import com.disasterinfo.eodiisseo.collection.operations.*;
import com.disasterinfo.eodiisseo.disaster.collection.*;
import com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.orm.jpa.JpaTransactionManager;
import javax.sql.DataSource;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
/** Real new TLS PostgreSQL on 127.0.0.2:55433, own TEMP data/CA/LOGINs. No audit constraint changes. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_GATEWAY_TLS_TESTS",matches="true") @Timeout(120)
class CollectionGatewayTlsDomainPostgresTest {
 TlsFixture f;
 abstract static class TlsFixture extends CollectionProductionLoginPostgresTest {
  final Path root;final CollectionTlsPolicy tls;final char[] adminPassword;CollectionLoginPool bootstrap,adminDb;
  final Map<String,java.util.concurrent.atomic.AtomicLong> epochs=new HashMap<>();
  TlsFixture()throws Exception{
   root=Path.of(Objects.requireNonNull(System.getenv("EODIISSEO_GATEWAY_TLS_ROOT"))).toRealPath();
   if(!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())||!root.getFileName().toString().matches("c4c4-gateway-tls-[a-f0-9]{32}"))throw new IllegalStateException("Owned TLS root required");
   tls=new CollectionTlsPolicy(root.resolve("ca.crt"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("ca.crt")))),Instant.now().plusSeconds(3600));adminPassword=Files.readString(root.resolve("bootstrap.password")).toCharArray();
  }
  String url(String database){return "jdbc:postgresql://127.0.0.2:55433/"+database;}
  @Override DataSource bootstrapSource(){
   bootstrap=new CollectionLoginPool(url("postgres"),"c4c4_bootstrap",adminPassword,"tls-gateway-bootstrap",2,tls,Instant.now().plusSeconds(1800),1,()->1);var db=new JdbcTemplate(bootstrap);
   try{if(!Path.of(db.queryForObject("show data_directory",String.class)).toRealPath().equals(root.resolve("data").toRealPath()))throw new IllegalStateException("Wrong data directory");}catch(java.io.IOException rejected){throw new IllegalStateException("Owned data directory required");}
   if(!"127.0.0.2".equals(db.queryForObject("select host(inet_server_addr())",String.class))||db.queryForObject("select inet_server_port()",Integer.class)!=55433||!Boolean.TRUE.equals(db.queryForObject("select ssl from pg_stat_ssl where pid=pg_backend_pid()",Boolean.class)))throw new IllegalStateException("Wrong TLS server");return bootstrap;
  }
  @Override DataSource databaseSource(String database){return adminDb=new CollectionLoginPool(url(database),"c4c4_bootstrap",adminPassword,"tls-gateway-database",3,tls,Instant.now().plusSeconds(1800),1,()->1);}
  @Override DataSource as(String role){return pools.computeIfAbsent(role,r->{var epoch=epochs.computeIfAbsent(r,x->new java.util.concurrent.atomic.AtomicLong(1));return new CollectionLoginPool(url(f.database),r,secrets.get(r),"tls-gateway-role",1,tls,Instant.now().plusSeconds(300),epoch.get(),epoch::get);});}
  @Override void operationsFixture(){/* Runtime DISPOSABLE_AUDIT service is intentionally NOT used for this different endpoint. */}
  @Override void approveFixture(){
   UUID parent=null;for(String step:List.of("STOP_VERIFIED","DRAIN_VERIFIED","MIGRATIONS_VERIFIED")){UUID id=UUID.randomUUID();f.sql.update("insert into collection_transition_review values(?,?,?,?,?,?,?,clock_timestamp()+interval '1 hour',true,clock_timestamp())",id,parent,step,f.ops.memberId(),f.ops.sessionId(),"SYNTHETIC:TLS_OWNED_FIXTURE","a".repeat(64));parent=id;}
  }
  @Override void closeConnections(){super.closeConnections();if(adminDb!=null)adminDb.close();}
  void dispose(){try{close();}finally{if(bootstrap!=null)bootstrap.close();Arrays.fill(adminPassword,'\0');}}
 }
 @BeforeEach void open()throws Exception{f=new TlsFixture(){};f.open();f.fixture();}
 @AfterEach void close(){if(f!=null)f.dispose();}
 @Test void actualTlsAndPhysicalJpaJdbcIdentityRetainAllOriginalAuditGuards(){
  assertTrue(f.gate.queryForObject("select ssl from pg_stat_ssl where pid=pg_backend_pid()",Boolean.class));assertEquals(f.gateway,f.gate.queryForObject("select session_user::text",String.class));
  assertThrows(IllegalArgumentException.class,()->CollectionOperationMode.DISPOSABLE_AUDIT.verify(f.ds));
  f.workerCannotWriteReadCapabilitiesOrUseGatewayFunctions();f.jpaAndJdbcCommitOrRollbackOnTheSamePhysicalTransaction();
 }
 @Test void realProjectionRevisionAndOutboxRemainAtomicOverTls(){
  f.ready();var claim=f.claim();var fixture=f.gatewayApp.getBean(OfficialCollectionProjectionPostgresTest.Fixture.class);var normalizer=f.gatewayApp.getBean(OfficialRecordNormalizer.class);String incident="SYNTHETIC-TLS-"+UUID.randomUUID();
  for(int seq=1;seq<=2;seq++){
   var row=Map.<String,Object>of("stnId","108","tmFc",seq==1?"202611011200":"202611011300","tmSeq",Integer.toString(seq),"fcTp","3","tmEqk","20261101115930","lat",seq==1?"36.1":"36.2","lon","127.1","loc","SYNTHETIC TLS "+seq);
   String raw=OfficialProviderClient.canonical(row),hash=com.disasterinfo.eodiisseo.auth.service.AuthService.hash(raw),announcement=OfficialRecordNormalizer.identity(OfficialProviderClient.Provider.EARTHQUAKE,row);
   fixture.links.put(f.f.source+":"+announcement+":"+hash,new VerifiedIncidentLinkPort.Link(f.f.source,OfficialProviderClient.Provider.EARTHQUAKE,announcement,hash,incident,seq,seq==1?Instant.parse("2026-11-01T03:00:00Z"):Instant.parse("2026-11-01T04:00:00Z"),Instant.parse("2026-11-01T02:59:30Z"),VerifiedIncidentLinkPort.State.OCCURRED,null,java.net.URI.create("https://www.kma.go.kr/SYNTHETIC_TLS"),"SYNTHETIC_TLS_ONLY"));

   // Earthquake correction is an OCCURRED incident with a new bulletin/revision, never a wildfire ONGOING state.
   if(seq==2){
    String evidenceKey=f.f.source+":"+announcement+":"+hash;var validLink=fixture.links.get(evidenceKey);
    fixture.links.put(evidenceKey,new VerifiedIncidentLinkPort.Link(validLink.source(),validLink.provider(),validLink.announcement(),validLink.contentSha256(),validLink.incidentId(),validLink.officialSequence(),validLink.issuedAt(),validLink.occurredAt(),VerifiedIncidentLinkPort.State.ONGOING,null,validLink.evidenceUrl(),validLink.contractVersion()));
    long before=f.count("source_disaster_revision"),outbox=f.count("disaster_runtime_outbox");
    assertEquals("INVALID_INCIDENT_EVIDENCE",f.writes.write(f.handle(),f.f.source,ProductionCollectionWriteGateway.Phase.PROJECTION,claim,()->normalizer.normalize(f.f.source,OfficialProviderClient.Provider.EARTHQUAKE,row,raw,Instant.now()).classification()));
    assertEquals(before,f.count("source_disaster_revision"));assertEquals(outbox,f.count("disaster_runtime_outbox"));fixture.links.put(evidenceKey,validLink);
   }
   long revisions=f.count("source_disaster_revision"),events=f.count("disaster_runtime_outbox");
   assertThrows(RuntimeException.class,()->f.writes.write(f.handle(),f.f.source,ProductionCollectionWriteGateway.Phase.PROJECTION,claim,()->{normalizer.normalize(f.f.source,OfficialProviderClient.Provider.EARTHQUAKE,row,raw,Instant.now());throw new IllegalStateException("SYNTHETIC rollback");}));assertEquals(revisions,f.count("source_disaster_revision"));assertEquals(events,f.count("disaster_runtime_outbox"));
   assertEquals("EARTHQUAKE_LINKED",f.writes.write(f.handle(),f.f.source,ProductionCollectionWriteGateway.Phase.PROJECTION,claim,()->normalizer.normalize(f.f.source,OfficialProviderClient.Provider.EARTHQUAKE,row,raw,Instant.now()).classification()));assertEquals(revisions+1,f.count("source_disaster_revision"));assertTrue(f.count("disaster_runtime_outbox")>events);
   long complete=f.count("disaster_runtime_outbox");assertEquals("INCIDENT_DUPLICATE",f.writes.write(f.handle(),f.f.source,ProductionCollectionWriteGateway.Phase.PROJECTION,claim,()->normalizer.normalize(f.f.source,OfficialProviderClient.Provider.EARTHQUAKE,row,raw,Instant.now()).classification()));assertEquals(complete,f.count("disaster_runtime_outbox"));
  }
  assertEquals(1,f.count("disaster"));assertEquals(2,f.count("source_disaster_revision"));
 }
 @Test void sessionRevocationAndPoolResetAreStillRejected()throws Exception{
  f.pooledSessionSettingsAndTransactionContextNeverLeak();f.f.sql.update("update auth_session set revoked_at=now() where session_id=?",f.f.ops.sessionId());assertThrows(RuntimeException.class,()->f.claim());
 }
 @Test void wrongFenceAndExpiredLeaseAreRejectedOverTls()throws Exception{f.wrongFenceAndExpiredLeaseAreRejected();}
 @Test void terminatingOnlyTheOwnedTlsBackendDuringDeferredCommitRollsBack()throws Exception{
  f.ready();var claim=f.claim();f.f.sql.execute("create function tls_commit_delay_fixture() returns trigger language plpgsql as $$ begin perform pg_sleep(20);return null;end $$");f.f.sql.execute("create constraint trigger tls_commit_delay_fixture after insert on projection_tx_probe deferrable initially deferred for each row execute function tls_commit_delay_fixture()");
  var pid=new java.util.concurrent.atomic.AtomicInteger();var entered=new CountDownLatch(1);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var write=pool.submit(()->f.writes.write(f.handle(),f.f.source,ProductionCollectionWriteGateway.Phase.EXECUTION,claim,()->{pid.set(f.gate.queryForObject("select pg_backend_pid()",Integer.class));f.gate.update("insert into projection_tx_probe values(?)",UUID.randomUUID());entered.countDown();return true;}));
   assertTrue(entered.await(5,TimeUnit.SECONDS));boolean committing=false;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
   while(System.nanoTime()<end){if(f.f.sql.queryForObject("select count(*) from pg_stat_activity where pid=? and datname=? and usename=? and wait_event='PgSleep'",Long.class,pid.get(),f.f.database,f.gateway)>0){committing=true;break;}Thread.sleep(20);}assertTrue(committing);
   assertTrue(f.f.sql.queryForObject("select pg_terminate_backend(pid) from pg_stat_activity where pid=? and datname=? and usename=?",Boolean.class,pid.get(),f.f.database,f.gateway));assertThrows(ExecutionException.class,()->write.get(10,TimeUnit.SECONDS));assertEquals(0,f.count("projection_tx_probe"));
  }finally{f.f.sql.execute("drop trigger tls_commit_delay_fixture on projection_tx_probe");f.f.sql.execute("drop function tls_commit_delay_fixture()");}
 }
 @Test void credentialRotationRejectsCachedPoolAndNewGatewayUsesSameDatabase()throws Exception{
  f.ready();var claim=f.claim();char[] replacement=UUID.randomUUID().toString().replace("-","").repeat(2).toCharArray();
  try(var c=f.ds.getConnection();var statement=c.createStatement()){try{statement.execute("alter role "+f.gateway+" password '"+new String(replacement)+"'");}catch(SQLException rejected){throw new IllegalStateException("Test gateway rotation failed");}}
  f.epochs.get(f.gateway).incrementAndGet();assertThrows(RuntimeException.class,()->f.writes.write(f.handle(),f.f.source,ProductionCollectionWriteGateway.Phase.EXECUTION,claim,()->true));
  try(var pool=new CollectionLoginPool(f.url(f.f.database),f.gateway,replacement,"rotated-gateway",1,f.tls,Instant.now().plusSeconds(60),2,()->2);var context=f.context(pool)){
   var jdbc=new JdbcTemplate(pool);var gateway=new ProductionCollectionWriteGateway(jdbc,context.getBean(JpaTransactionManager.class));
   assertTrue(gateway.write(f.handle(),f.f.source,ProductionCollectionWriteGateway.Phase.EXECUTION,claim,()->{jdbc.update("insert into projection_tx_probe values(?)",UUID.randomUUID());return true;}));assertEquals(1,f.count("projection_tx_probe"));
  }finally{Arrays.fill(replacement,'\0');}
 }
}

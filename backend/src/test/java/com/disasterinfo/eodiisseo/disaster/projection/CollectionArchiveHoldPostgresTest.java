package com.disasterinfo.eodiisseo.disaster.projection;
import com.disasterinfo.eodiisseo.collection.operations.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Timeout(90)
class CollectionArchiveHoldPostgresTest {
 final CollectionProductionLoginPostgresTest f=new CollectionProductionLoginPostgresTest();
 @BeforeAll void open(){f.open();f.f.sql.execute("grant execute on function collection_production.record_archive_hold(uuid,uuid,uuid,uuid,text,uuid) to "+f.operator);}
 @BeforeEach void fixture(){f.fixture();}
 @AfterAll void close(){f.close();}
 @Test void holdAuditRequiresActualOpsSessionAndIsImmutableAndReferenced()throws Exception{
  f.rawAndQuarantineCommitSeparatelyFromFailedProjection();UUID attempt=f.f.sql.queryForObject("select attempt_id from collection_http_archive",UUID.class),decision=UUID.randomUUID();
  var actor=f.f.ops;
  assertEquals(decision,f.ops.queryForObject("select collection_production.record_archive_hold(?,?,?,?,?,?)",UUID.class,actor.memberId(),actor.sessionId(),attempt,decision,"ERASURE_NOT_APPROVED",null));
  assertThrows(RuntimeException.class,()->f.ops.queryForList("select collection_production.record_archive_hold(?,?,?,?,?,?)",actor.memberId(),actor.sessionId(),attempt,decision,"ERASURE_NOT_APPROVED",null));
  assertThrows(RuntimeException.class,()->f.gate.queryForList("select collection_production.record_archive_hold(?,?,?,?,?,?)",actor.memberId(),actor.sessionId(),attempt,UUID.randomUUID(),"ERASURE_NOT_APPROVED",null));
  assertThrows(RuntimeException.class,()->f.f.sql.update("delete from collection_production.archive_hold_audit where decision_id=?",decision));
  assertThrows(RuntimeException.class,()->f.f.sql.update("delete from collection_http_archive where attempt_id=?",attempt));
  assertEquals(1,f.f.sql.queryForObject("select count(*) from pg_constraint where conrelid='collection_production.archive_hold_audit'::regclass and confrelid='collection_http_archive'::regclass and confdeltype='a'",Integer.class));
  assertNotEquals(actor.memberId().toString(),f.f.sql.queryForObject("select actor_sha256 from collection_production.archive_hold_audit",String.class));
  f.f.sql.update("update auth_session set revoked_at=now() where session_id=?",actor.sessionId());
  assertThrows(RuntimeException.class,()->f.ops.queryForList("select collection_production.record_archive_hold(?,?,?,?,?,?)",actor.memberId(),actor.sessionId(),attempt,UUID.randomUUID(),"ERASURE_NOT_APPROVED",null));
  assertEquals(1,f.f.sql.queryForObject("select count(*) from collection_production.archive_hold_audit",Integer.class));
 }
 @Test void unapprovedPolicyNeverDeletesAndUsesActualReferences(){
  f.rawAndQuarantineCommitSeparatelyFromFailedProjection();UUID attempt=f.f.sql.queryForObject("select attempt_id from collection_http_archive",UUID.class);
  var store=new JdbcCollectionAssurance(f.f.sql,f.f.manager,f.f.service,CollectionOperationMode.DISPOSABLE_AUDIT,new CollectionAssurance.Verifier(Map.of()));
  var plan=new CollectionArchiveLifecycle(store).inspect(f.f.sql,attempt,Instant.now().plusSeconds(7200));
  assertFalse(plan.policyApproved());assertFalse(plan.deleteAllowed());assertTrue(plan.blockers().contains("ARCHIVE_RETENTION_ACL_APPROVAL_REQUIRED"));
  assertEquals(1,f.f.sql.queryForObject("select count(*) from collection_http_archive",Integer.class));
 }
}

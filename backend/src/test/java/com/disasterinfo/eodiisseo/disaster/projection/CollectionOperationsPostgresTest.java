package com.disasterinfo.eodiisseo.disaster.projection;
import com.disasterinfo.eodiisseo.collection.operations.*;
import com.disasterinfo.eodiisseo.collection.operations.CollectionOperationalEvidencePort.*;
import com.disasterinfo.eodiisseo.collection.ownership.*;
import com.disasterinfo.eodiisseo.collection.execution.*;
import com.disasterinfo.eodiisseo.auth.repository.*;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real disposable PostgreSQL + actual Spring JPA/JDBC + persisted OPS sessions. All operational evidence is synthetic. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Timeout(90)
class CollectionOperationsPostgresTest {
    JdbcTemplate admin,sql;String database;AnnotationConfigApplicationContext app;JpaTransactionManager manager;
    CollectionOperationsService service;CollectionOwnership ownership;JdbcCollectionExecutionStore journal;
    MemberPrincipal ops;UUID source,target;Witness evidence;long epoch;UUID upgradeRun;UUID upgradeSource;
    static final Instant UNTIL=Instant.parse("2099-01-01T00:00:00Z");
    static class Witness implements CollectionOperationalEvidencePort {
        boolean stopped=true,schedulers=true,completed=true,synthetic=true,quota=true;String version="SYNTHETIC-4A-v1";
        final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        public Map<Kind,Evidence> current(){var result=new EnumMap<Kind,Evidence>(Kind.class);for(var kind:Kind.values())result.put(kind,new Evidence(kind,version,"a".repeat(64),"SYNTHETIC:"+kind,UNTIL,true,synthetic));return result;}
        public boolean quotaApproved(String scope,Instant start,Instant end,long limit,int interval,String ref){return quota&&scope.equals("SYNTHETIC-quota")&&limit==10&&interval==0&&ref.equals("SYNTHETIC quota approval");}
        public boolean legacyWorkersStopped(){return stopped;}public boolean legacySchedulersDisabled(){return schedulers;}
        public boolean completionVerified(UUID run,String snapshot,String reference){return completed&&reference.equals("SYNTHETIC:NO_ACTIVE_REQUEST");}
        public CollectionArchivePolicy archivePolicy(){return new CollectionArchivePolicy(Duration.ofHours(1),"SYNTHETIC private ACL",Set.of());}
        public Transport transport(){return (contract,request)->{calls.incrementAndGet();throw new AssertionError("No HTTP in preparation");};}
        public CollectionProviderPort.Evidence collectionContract(String key){return new CollectionProviderPort.Evidence(true,true,true,true,"SYNTHETIC HTTP only");}
    }
    @BeforeAll void open(){
        String url=System.getenv("EODIISSEO_AUDIT_JDBC_URL");if(!"jdbc:postgresql://127.0.0.1:55433/postgres".equals(url)||!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")))throw new IllegalStateException("Approved disposable audit only");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"eodiisseo_audit",""));database="collection4a_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+database);
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+database,"eodiisseo_audit","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime","classpath:db/collection-executor","classpath:db/collection-projection").load().migrate();
        sql=new JdbcTemplate(ds);assertThrows(IllegalStateException.class,()->CollectionReadiness.requireSchema(sql),"Missing 4A migration must block preparation");upgradeSource=sql.queryForObject("select source_id from data_source where endpoint_key='KMA_EARTHQUAKE'",UUID.class);upgradeRun=UUID.randomUUID();
        sql.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type) values(?,?,'LIVE',now(),'RUNNING','MANUAL')",upgradeRun,upgradeSource);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime","classpath:db/collection-executor","classpath:db/collection-projection","classpath:db/collection-operations").load().migrate();
        assertEquals("RUNNING",sql.queryForObject("select status from collection_run where run_id=?",String.class,upgradeRun));
        assertNull(sql.queryForObject("select execution_owner from collection_run where run_id=?",String.class,upgradeRun));
        sql.execute("create table projection_tx_probe(id uuid primary key)");
        app=new AnnotationConfigApplicationContext();app.getEnvironment().setActiveProfiles("collection-projection-audit-only");app.registerBean("dataSource",DataSource.class,()->ds);app.register(OfficialCollectionProjectionPostgresTest.Config.class);app.refresh();
        manager=app.getBean("transactionManager",JpaTransactionManager.class);
        System.out.println("STAGE4A_POSTGRES_VERSION="+sql.queryForObject("show server_version",String.class)+" manager="+manager.getClass().getSimpleName());
    }
    @AfterAll void close(){if(app!=null)app.close();if(database!=null&&database.matches("collection4a_[a-f0-9]{32}"))admin.execute("drop database "+database);}
    @BeforeEach void fixture(){
        sql.execute("truncate collection_transition_review,collection_component_approval,collection_operation_approval,collection_approval_revocation,collection_resolution_detail,collection_run,member cascade");
        sql.update("update data_source set collection_assignment_epoch=null");sql.update("delete from collection_quota_window");
        source=upgradeSource;target=UUID.randomUUID();epoch++;evidence=new Witness();
        sql.update("insert into collection_contract values('KMA_PORTAL_EQK_MSG',true) on conflict do nothing");
        sql.update("insert into collection_target values(?,?,'KMA_PORTAL_EQK_MSG',true)",target,"SYNTHETIC-"+target);
        sql.update("insert into collection_source_assignment values(?,?,'KMA_EARTHQUAKE',?,'KMA_PORTAL_EQK_MSG','SYNTHETIC-quota','COMMON_COLLECTION_V1','SYNTHETIC assignment',true,true)",source,epoch,target);
        sql.update("update data_source set collection_assignment_epoch=? where source_id=?",epoch,source);
        sql.update("insert into collection_quota_window values('SYNTHETIC-quota',now()-interval '1 hour',now()+interval '1 hour',10,0,0,now(),'SYNTHETIC quota approval')");
        ops=member(true);service=service(CollectionOperationMode.DISPOSABLE_AUDIT,evidence);
        ownership=new CollectionOwnership(new JdbcCollectionOwnershipStore(sql,manager),new CollectionContractRegistry(),Duration.ofSeconds(30));journal=new JdbcCollectionExecutionStore(sql,manager);
    }
    MemberPrincipal member(boolean role){UUID m=UUID.randomUUID(),s=UUID.randomUUID();sql.update("insert into member values(?,'SYNTHETIC admin','ACTIVE',now(),null)",m);sql.update("insert into auth_session values(?,?,?,now(),now()+interval '336 hours',null)",s,m,UUID.randomUUID().toString().replace("-","").repeat(2));if(role)sql.update("insert into member_role values(?,'OPS',now())",m);return new MemberPrincipal(m,s);}
    CollectionOperationsService service(CollectionOperationMode mode,Witness witness){return new CollectionOperationsService(sql,manager,new SessionAccessService(new AuthRepository(app.getBean(OwnedDatabase.class))),mode,witness);}
    UUID legacy(){UUID id=UUID.randomUUID();sql.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type) values(?,?,'LIVE',now(),'RUNNING','MANUAL')",id,source);sql.update("insert into collection_job_lock values(?,'LIVE',?,now()-interval '1 hour')",source,id);return id;}
    UUID stop(){return service.review(ops,"STOP_VERIFIED",null,"SYNTHETIC:FLEET_STOP",UNTIL);}
    CollectionOperationsService.Resolution release(UUID run,UUID stop){return new CollectionOperationsService.Resolution(UUID.randomUUID(),run,service.inspect(ops,run).digest(),"RELEASE_CONFIRMED_STOPPED","SYNTHETIC:REVIEWED_STOP",stop,"SYNTHETIC:NO_ACTIVE_REQUEST");}
    CollectionReadiness readiness(){return new CollectionReadiness(sql,manager,new StandardEnvironment(),evidence,CollectionOperationMode.DISPOSABLE_AUDIT);}
    void allApproved(){for(var kind:Kind.values())service.approveComponent(ops,kind);service.approveOperation(ops,new CollectionOperationsService.Operation(source,source,"SYNTHETIC-domain-v1","SYNTHETIC:DOMAIN",true,true,UNTIL));UUID s=stop();UUID d=service.review(ops,"DRAIN_VERIFIED",s,"SYNTHETIC:DRAIN",UNTIL);service.review(ops,"MIGRATIONS_VERIFIED",d,"SYNTHETIC:MIGRATIONS",UNTIL);}
    long count(String table){return sql.queryForObject("select count(*) from "+table,Long.class);}
    @Test void migrationUpgradeAndOptionalSchemaValidated(){CollectionReadiness.requireSchema(sql);assertEquals(1,sql.queryForObject("select count(*) from flyway_schema_history where version='2026111004' and success",Integer.class));}
    @Test void legacyUnknownRunRemainsUnchangedOnInspection(){UUID run=legacy();var s=service.inspect(ops,run);assertEquals("RUNNING",s.execution().get("status"));assertNull(s.execution().get("execution_owner"));assertEquals(1,s.sourceLocks().size());assertEquals(0,count("collection_run_resolution"));}
    @Test void oldWorkerCanStillSeeUnknownWorkAndNewRuntimeMustRemainDisabled(){UUID run=legacy();assertEquals(run,sql.queryForObject("select run_id from collection_run where status='RUNNING' and heartbeat_at is null",UUID.class));assertFalse(readiness().inspect(Set.of(source)).prepared());assertEquals(0,evidence.calls.get());}
    @Test void anonymousAdminRejected(){assertEquals(401,assertThrows(ApiProblem.class,()->service.investigate(null)).status());}
    @Test void nonOpsRejected(){var user=member(false);assertEquals(403,assertThrows(ApiProblem.class,()->service.investigate(user)).status());}
    @Test void revokedSessionRejected(){sql.update("update auth_session set revoked_at=now() where session_id=?",ops.sessionId());assertEquals(401,assertThrows(ApiProblem.class,()->service.investigate(ops)).status());}
    @Test void expiredSessionRejected(){sql.update("update auth_session set created_at=now()-interval '336 hours 1 minute',expires_at=now()-interval '1 minute' where session_id=?",ops.sessionId());assertEquals(401,assertThrows(ApiProblem.class,()->service.investigate(ops)).status());}
    @Test void roleRemovalIsRechecked(){sql.update("delete from member_role where member_id=?",ops.memberId());assertEquals(403,assertThrows(ApiProblem.class,()->service.investigate(ops)).status());}
    @Test void keepHeldDoesNotChangeRunOrLock(){UUID run=legacy();var s=service.inspect(ops,run);service.resolve(ops,new CollectionOperationsService.Resolution(UUID.randomUUID(),run,s.digest(),"KEEP_HELD","SYNTHETIC:INVESTIGATE",null,null));assertEquals(s.digest(),service.inspect(ops,run).digest());assertEquals(1,count("collection_resolution_detail"));}
    @Test void elapsedTimeAloneCannotReleaseUnknown(){UUID run=legacy();var r=release(run,null);assertThrows(ApiProblem.class,()->service.resolve(ops,r));assertEquals("RUNNING",service.inspect(ops,run).execution().get("status"));assertEquals(0,count("collection_run_resolution"));}
    @Test void completionProofMustBeIndependentlyReviewed(){UUID run=legacy(),stop=stop();evidence.completed=false;assertThrows(ApiProblem.class,()->service.resolve(ops,release(run,stop)));assertEquals(1,count("collection_job_lock"));}
    @Test void stoppedFleetProofMandatory(){evidence.stopped=false;assertThrows(ApiProblem.class,this::stop);assertEquals(0,count("collection_transition_review"));}
    @Test void unknownRunReleaseIsAtomicAndKeepsOwnerUnknown(){UUID run=legacy();var r=release(run,stop());service.resolve(ops,r);var s=service.inspect(ops,run);assertEquals("FAILED",s.execution().get("status"));assertNull(s.execution().get("execution_owner"));assertEquals(0,count("collection_job_lock"));assertEquals(1,count("collection_run_resolution"));assertEquals(1,count("collection_resolution_detail"));assertThrows(ApiProblem.class,()->service.resolve(ops,r));}
    @Test void staleSnapshotCannotReleaseChangedRun(){UUID run=legacy();var r=release(run,stop());sql.update("update collection_run set heartbeat_at=now() where run_id=?",run);assertEquals("COLLECTION_STATE_CHANGED",assertThrows(ApiProblem.class,()->service.resolve(ops,r)).code());assertEquals(0,count("collection_run_resolution"));}
    @Test void terminalOrphanReleasePreservesSuccessfulRun(){UUID run=legacy();sql.update("update collection_run set status='SUCCESS',finished_at=now() where run_id=?",run);service.resolve(ops,release(run,stop()));assertEquals("SUCCESS",service.inspect(ops,run).execution().get("status"));assertEquals(0,count("collection_job_lock"));}
    @Test void uncertainAttemptRequiresProofAndRetainsQuarantineAndQuota(){
        var claim=ownership.claim(source,"LIVE");journal.start(claim,new CollectionProviderPort.Request(LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1,5,Duration.ofSeconds(3)),evidence.collectionContract("KMA_PORTAL_EQK_MSG"));
        var attempt=journal.reserve(claim,1,1).orElseThrow();var policy=new CollectionArchivePolicy(Duration.ofHours(1),"SYNTHETIC private ACL",Set.of());
        byte[] bytes="{\"SYNTHETIC\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);journal.received(attempt,new CollectionProviderPort.Reply(200,bytes,Duration.ZERO),policy.inspect(bytes),policy);journal.unknown(attempt);
        var r=release(claim.run(),stop());evidence.completed=false;assertThrows(ApiProblem.class,()->service.resolve(ops,r));assertEquals("UNKNOWN",sql.queryForObject("select state from collection_http_attempt",String.class));
        evidence.completed=true;service.resolve(ops,r);assertEquals("REJECTED",sql.queryForObject("select state from collection_http_attempt",String.class));assertEquals("QUARANTINE",sql.queryForObject("select classification from collection_http_archive",String.class));assertEquals(1,sql.queryForObject("select reserved from collection_quota_window",Integer.class));assertEquals(1,sql.queryForObject("select fence_token from collection_target_lease",Integer.class));assertNull(sql.queryForObject("select claim_id from collection_target_lease",UUID.class));
        assertThrows(RuntimeException.class,()->ownership.finish(claim,CollectionOwnership.Outcome.SUCCESS,()->{}));
    }
    @Test void rollbackRestoresRunLockAndAudit(){UUID run=legacy();var r=release(run,stop());sql.execute("create function synthetic_resolution_failure() returns trigger language plpgsql as $$ begin raise exception 'SYNTHETIC injected rollback'; end $$");sql.execute("create trigger synthetic_resolution_failure before delete on collection_job_lock for each row execute function synthetic_resolution_failure()");try{assertThrows(RuntimeException.class,()->service.resolve(ops,r));assertEquals("RUNNING",service.inspect(ops,run).execution().get("status"));assertEquals(1,count("collection_job_lock"));assertEquals(0,count("collection_run_resolution"));assertEquals(0,count("collection_resolution_detail"));}finally{sql.execute("drop trigger synthetic_resolution_failure on collection_job_lock");sql.execute("drop function synthetic_resolution_failure()");}}
    @Test void concurrentAdminsCannotDoubleRelease() throws Exception {UUID run=legacy();var r=release(run,stop());try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var a=pool.submit(()->{try{service.resolve(ops,r);return true;}catch(ApiProblem denied){return false;}});var b=pool.submit(()->{try{service.resolve(ops,r);return true;}catch(ApiProblem denied){return false;}});assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}assertEquals(1,count("collection_resolution_detail"));}
    @Test void readinessRequiresApprovalsAndRealSharedJpaTransaction(){var report=readiness().inspect(Set.of(source));assertFalse(report.prepared());assertFalse(report.executionAllowed());assertTrue(report.blockers().contains("UNIQUE_OPERATION_APPROVAL_REQUIRED"));assertFalse(report.blockers().contains("JPA_JDBC_TRANSACTION_MISMATCH"));}
    @Test void approvedPreparationStillCannotExecute(){allApproved();var check=readiness();assertEquals(List.of(),check.inspect(Set.of(source)).blockers());var coordinator=new CollectionCoordinator(ownership,journal,List.of(),new CollectionCoordinator.Policy(false,1,1,1,Duration.ofSeconds(1),Duration.ZERO),new CollectionArchivePolicy(Duration.ofHours(1),"SYNTHETIC private",Set.of()));var prepared=new PreparedCollectionRuntime(coordinator,check,Set.of(source));assertTrue(prepared.inspect().prepared());assertFalse(prepared.inspect().executionAllowed());assertEquals(CollectionExecutionStore.State.DISABLED,prepared.collect(source,LocalDate.now(),LocalDate.now(),5).state());assertEquals(0,evidence.calls.get());assertEquals(0,count("collection_run"));}
    @Test void syntheticApprovalsNeverSatisfyProduction(){allApproved();var production=new CollectionReadiness(sql,manager,new StandardEnvironment(),evidence,CollectionOperationMode.PRODUCTION);assertFalse(production.inspect(Set.of(source)).prepared());assertThrows(ApiProblem.class,()->service(CollectionOperationMode.PRODUCTION,evidence).approveComponent(ops,Kind.DOMAIN_POLICY));}
    @Test void revocationBlocksPreviouslyPreparedRuntime(){allApproved();UUID id=sql.queryForObject("select approval_id from collection_operation_approval",UUID.class);service.revoke(ops,id,"SYNTHETIC:WITHDRAWN");assertFalse(readiness().inspect(Set.of(source)).prepared());assertThrows(ApiProblem.class,()->service.revoke(ops,id,"SYNTHETIC:TWICE"));}
    @Test void componentVersionChangeInvalidatesApproval(){allApproved();evidence.version="SYNTHETIC-v2";assertFalse(readiness().inspect(Set.of(source)).prepared());}
    @Test void frozenReviewInvalidatesPreparedChain(){allApproved();service.review(ops,"FROZEN",null,"SYNTHETIC:INCIDENT_REVIEW",UNTIL);assertFalse(readiness().inspect(Set.of(source)).prepared());}
    @Test void invalidCanonicalSourceIsRejected(){assertThrows(ApiProblem.class,()->service.approveOperation(ops,new CollectionOperationsService.Operation(source,UUID.randomUUID(),"SYNTHETIC-v1","SYNTHETIC:DOMAIN",true,true,UNTIL)));assertEquals(0,count("collection_operation_approval"));}
    @Test void quotaExhaustionBlocksReadiness(){allApproved();sql.update("update collection_quota_window set reserved=request_limit");assertTrue(readiness().inspect(Set.of(source)).blockers().contains("UNIQUE_APPROVED_QUOTA_REQUIRED"));}
    @Test void activeRunPreventsDrainAndPreparation(){UUID stop=stop();legacy();assertThrows(ApiProblem.class,()->service.review(ops,"DRAIN_VERIFIED",stop,"SYNTHETIC:DRAIN",UNTIL));assertFalse(readiness().inspect(Set.of(source)).prepared());}
    @Test void historyIsAppendOnlyAndNoSecretPayloadIsExposed(){UUID run=legacy();stop();assertThrows(RuntimeException.class,()->sql.update("update collection_transition_review set evidence_reference='SYNTHETIC:ALTERED'"));var s=service.inspect(ops,run);assertFalse(s.execution().containsKey("error_message"));assertFalse(s.attempts().toString().contains("raw_body"));assertThrows(ApiProblem.class,()->service.review(ops,"STOP_VERIFIED",null,"https://invalid/?serviceKey=SECRET",UNTIL));}
    @Test void quotaApprovalCannotBeInferredFromNonemptyReference(){allApproved();evidence.quota=false;assertTrue(readiness().inspect(Set.of(source)).blockers().contains("QUOTA_POLICY_APPROVAL_REQUIRED"));}
    @Test void migrationAppliesToEmptyDisposableDatabase(){
        String empty="collection4a_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+empty);
        try{var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+empty,"eodiisseo_audit","");var f=Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime","classpath:db/collection-executor","classpath:db/collection-projection","classpath:db/collection-operations").load();f.migrate();f.validate();CollectionReadiness.requireSchema(new JdbcTemplate(ds));}finally{admin.execute("drop database "+empty);}
    }
    @Test void aliasCanonicalDisagreementBlocksPreparation(){
        UUID a=sql.queryForObject("select source_id from data_source where endpoint_key='SAFETYDATA_DISASTER_MESSAGE'",UUID.class),b=UUID.randomUUID(),t=UUID.randomUUID();
        sql.update("insert into data_source(source_id,name,source_kind,endpoint_key,collection_interval_minutes,active) select ?,'SYNTHETIC alias',source_kind,'MOIS_DISASTER_MESSAGES',collection_interval_minutes,true from data_source where source_id=?",b,a);
        sql.update("insert into collection_contract values('MOIS_MESSAGES',true) on conflict do nothing");sql.update("insert into collection_target values(?,?,'MOIS_MESSAGES',true)",t,"SYNTHETIC-alias-"+t);
        for(UUID id:List.of(a,b)){
            String alias=sql.queryForObject("select endpoint_key from data_source where source_id=?",String.class,id);
            sql.update("insert into collection_source_assignment values(?,?,?,?,'MOIS_MESSAGES','SYNTHETIC-quota','COMMON_COLLECTION_V1','SYNTHETIC alias assignment',true,true)",id,epoch,alias,t);
            sql.update("update data_source set collection_assignment_epoch=? where source_id=?",epoch,id);
            service.approveOperation(ops,new CollectionOperationsService.Operation(id,id,"SYNTHETIC-alias-v1","SYNTHETIC:ALIAS_DOMAIN",true,true,UNTIL));
        }
        assertTrue(readiness().inspect(Set.of(a,b)).blockers().contains("ALIAS_CANONICAL_CONFLICT"));
    }
    @Test void lateJournalAndResolutionUseExecutionBeforeAttemptLockOrder()throws Exception{
        var claim=ownership.claim(source,"LIVE");journal.start(claim,new CollectionProviderPort.Request(LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1,5,Duration.ofSeconds(3)),evidence.collectionContract("KMA_PORTAL_EQK_MSG"));var attempt=journal.reserve(claim,1,1).orElseThrow();
        var request=release(claim.run(),stop());var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var receipt=pool.submit(()->new JdbcCollectionOwnershipStore(sql,manager).transaction(tx->{
                sql.queryForList("select run_id from collection_http_execution where run_id=? for update",claim.run());entered.countDown();
                try{if(!proceed.await(10,TimeUnit.SECONDS))throw new IllegalStateException("SYNTHETIC latch timed out");}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IllegalStateException(stop);}
                journal.unknown(attempt);return true;
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var resolution=pool.submit(()->{try{service.resolve(ops,request);return "RELEASED";}catch(ApiProblem changed){return changed.code();}});
            try{
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<end){if(sql.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",Long.class)>0){waiting=true;break;}Thread.sleep(10);}
                assertTrue(waiting,"Second JDBC connection must actually wait for the held execution row");
            }finally{proceed.countDown();}
            assertTrue(receipt.get(5,TimeUnit.SECONDS));assertEquals("COLLECTION_STATE_CHANGED",resolution.get(5,TimeUnit.SECONDS));
        }
        assertEquals("UNKNOWN",sql.queryForObject("select state from collection_http_attempt",String.class));assertEquals("RUNNING",service.inspect(ops,claim.run()).execution().get("status"));assertEquals(0,count("collection_run_resolution"));
    }
    @Test void sessionExpiryWhileWaitingForCollectionGateDoesNotExtendAuthorization()throws Exception{
        sql.update("with expiry as (select clock_timestamp()+interval '2 seconds' as t) update auth_session set created_at=expiry.t-interval '336 hours',expires_at=expiry.t from expiry where session_id=?",ops.sessionId());
        var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var blocker=pool.submit(()->new JdbcCollectionOwnershipStore(sql,manager).transaction(tx->{sql.queryForList("select pg_advisory_xact_lock(78161108)");entered.countDown();try{if(!proceed.await(10,TimeUnit.SECONDS))throw new IllegalStateException("SYNTHETIC gate latch timeout");}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new IllegalStateException(stop);}return true;}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));var call=pool.submit(()->{try{service.investigate(ops);return 200;}catch(ApiProblem rejected){return rejected.status();}});
            try{
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                while(System.nanoTime()<end){if(sql.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and xact_start<(select expires_at from auth_session where session_id=?)",Long.class,ops.sessionId())>0){waiting=true;break;}Thread.sleep(10);}
                assertTrue(waiting,"The authenticated request must actually wait inside its already-started transaction");
                while(!sql.queryForObject("select clock_timestamp()>=expires_at from auth_session where session_id=?",Boolean.class,ops.sessionId())&&System.nanoTime()<end)Thread.sleep(10);
                assertTrue(sql.queryForObject("select clock_timestamp()>=expires_at from auth_session where session_id=?",Boolean.class,ops.sessionId()));
            }finally{proceed.countDown();}
            assertTrue(blocker.get(5,TimeUnit.SECONDS));assertEquals(401,call.get(5,TimeUnit.SECONDS));
        }
    }
}
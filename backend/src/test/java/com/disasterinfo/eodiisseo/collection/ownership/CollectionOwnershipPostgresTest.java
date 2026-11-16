package com.disasterinfo.eodiisseo.collection.ownership;

import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDBC/PostgreSQL. New UUID databases only on the expressly approved audit cluster. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class CollectionOwnershipPostgresTest {
    static final String BASE="jdbc:postgresql://127.0.0.1:55433/";
    JdbcTemplate admin,sql; DriverManagerDataSource ds; DataSourceTransactionManager manager;
    CollectionOwnership service; TransactionTemplate tx; final List<String> databases=new ArrayList<>();
    UUID source,alias,target;long epoch;
    String database(){
        String name="collection16_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create database "+name);databases.add(name);return name;
    }
    DriverManagerDataSource dataSource(String name){return new DriverManagerDataSource(BASE+name,"eodiisseo_audit","");}
    Flyway flyway(DriverManagerDataSource source){return Flyway.configure().dataSource(source).locations("classpath:db/migration").load();}
    @BeforeAll void open(){
        if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE"))||!(BASE+"postgres").equals(System.getenv("EODIISSEO_AUDIT_JDBC_URL")))
            throw new IllegalStateException("Approved disposable 55433 cluster only");
        admin=new JdbcTemplate(dataSource("postgres"));ds=dataSource(database());
        flyway(ds).migrate();sql=new JdbcTemplate(ds);
        System.out.println("COLLECTION16_POSTGRES_VERSION="+sql.queryForObject("show server_version",String.class));
        manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        service=new CollectionOwnership(new JdbcCollectionOwnershipStore(sql,manager),new CollectionContractRegistry(),Duration.ofSeconds(30));
        sql.execute("create table collection_test_writes(id uuid primary key)");
        source=sql.queryForObject("select source_id from data_source where endpoint_key='SAFETYDATA_DISASTER_MESSAGE'",UUID.class);
        alias=UUID.randomUUID();sql.update("insert into data_source values(?,'synthetic alias','DISASTER','MOIS_DISASTER_MESSAGES',5,true)",alias);
    }
    @AfterAll void close(){
        if(admin!=null)for(String name:databases){
            if(!name.matches("collection16_[a-f0-9]{32}"))throw new IllegalStateException("Unexpected database name");
            admin.execute("drop database "+name); // No FORCE: a leaked live connection is a test failure.
        }
    }
    @BeforeEach void fixture(){
        sql.update("update collection_target_lease set claim_id=null,run_id=null,lease_until=null");sql.update("delete from collection_job_lock");sql.update("delete from collection_run");sql.update("delete from collection_test_writes");
        epoch++;target=UUID.randomUUID();
        sql.update("insert into collection_contract values('MOIS_MESSAGES',true) on conflict do nothing");
        sql.update("insert into collection_target values(?,?,'MOIS_MESSAGES',true)",target,"synthetic-"+target);
        assign(source,"SAFETYDATA_DISASTER_MESSAGE",target,"MOIS_MESSAGES");assign(alias,"MOIS_DISASTER_MESSAGES",target,"MOIS_MESSAGES");
    }
    void assign(UUID id,String key,UUID t,String contract){
        sql.update("insert into collection_source_assignment values(?,?,?,?,?,?,'COMMON_COLLECTION_V1','SYNTHETIC approval',true,true)",id,epoch,key,t,contract,"synthetic-quota");
        sql.update("update data_source set collection_assignment_epoch=? where source_id=?",epoch,id);
    }
    void age(Claim claim){
        sql.update("update collection_target_lease set lease_until=clock_timestamp()-interval '1 second' where run_id=?",claim.run());
        sql.update("update collection_job_lock set lease_until=clock_timestamp()-interval '1 second' where run_id=?",claim.run());
    }
    @Test void freshMigrationAndConstraintsExist(){
        assertEquals(5,sql.queryForObject("select count(*) from flyway_schema_history where success and type='SQL'",Integer.class));
        assertEquals(1,sql.queryForObject("select count(*) from pg_indexes where indexname='uq_run_owned_target_active'",Integer.class));
        assertThrows(RuntimeException.class,()->sql.update("insert into collection_target_lease(target_id,mode,fence_token) values(?,'LIVE',0)",target));
        assertThrows(RuntimeException.class,()->sql.update("insert into collection_target_lease(target_id,mode,fence_token,claim_id) values(?,'LIVE',1,?)",target,UUID.randomUUID()));
        assertThrows(RuntimeException.class,()->sql.update("insert into collection_target values(?,?,'MISSING',false)",UUID.randomUUID(),"bad"));
    }
    @Test void legacyPositionalInsertAndPartialSnapshotRejected(){
        UUID legacy=UUID.randomUUID();
        sql.update("insert into collection_run values(?,?,'LIVE',clock_timestamp(),null,'RUNNING',0,null,null,'MANUAL',null,null,null,0)",legacy,source);
        assertNull(sql.queryForObject("select claim_id from collection_run where run_id=?",UUID.class,legacy));
        assertThrows(RuntimeException.class,()->sql.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type,fence_token) values(?,?,'LIVE',now(),'RUNNING','MANUAL',1)",UUID.randomUUID(),source));
    }
    @Test void sameTargetTwoConnectionsHaveOneWinner() throws Exception { compete(source,source); }
    @Test void aliasSourcesTwoConnectionsHaveOneWinner() throws Exception { compete(source,alias); }
    void compete(UUID first,UUID second) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var connected=new CountDownLatch(2);var pids=ConcurrentHashMap.<Integer>newKeySet();
            java.util.function.Function<UUID,Boolean> reserve=id->{try{return tx.execute(status->{
                pids.add(sql.queryForObject("select pg_backend_pid()",Integer.class));connected.countDown();
                try { if(!connected.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Two JDBC connections required"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                service.claim(id,"LIVE");return true;
            });}catch(IllegalStateException held){return false;}};
            Callable<Boolean> a=()->{start.await();return reserve.apply(first);};
            Callable<Boolean> b=()->{start.await();return reserve.apply(second);};
            var x=executor.submit(a);var y=executor.submit(b);start.countDown();
            assertNotEquals(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));
            assertEquals(2,pids.size(),"Two distinct PostgreSQL backend connections must contend");
            assertEquals(1,sql.queryForObject("select count(*) from collection_run where status='RUNNING'",Integer.class));
        }
    }
    @Test void separateTargetsAndContractsCanRemainActive(){
        UUID second=sql.queryForObject("select source_id from data_source where endpoint_key='KMA_WARNING'",UUID.class), t=UUID.randomUUID();
        sql.update("insert into collection_contract values('KMA_WARNING_LIST',true) on conflict do nothing");
        sql.update("insert into collection_target values(?,?,'KMA_WARNING_LIST',true)",t,"synthetic-"+t);
        assign(second,"KMA_WARNING",t,"KMA_WARNING_LIST");
        assertNotNull(service.claim(source,"LIVE"));assertNotNull(service.claim(second,"LIVE"));
    }
    @Test void heartbeatFinishExpireFenceAndNoDuplicateWrites(){
        var old=service.claim(source,"LIVE");service.heartbeat(old);assertFalse(service.expireReservation(old));age(old);
        assertThrows(IllegalStateException.class,()->service.heartbeat(old));assertTrue(service.expireReservation(old));
        var next=service.claim(alias,"LIVE");assertTrue(next.fence()>old.fence());assertNotEquals(next.claimId(),old.claimId());
        assertThrows(IllegalStateException.class,()->service.finish(old,Outcome.SUCCESS,()->sql.update("insert into collection_test_writes values(?)",UUID.randomUUID())));
        assertThrows(IllegalStateException.class,()->service.expireReservation(old));
        service.finish(next,Outcome.SUCCESS,()->sql.update("insert into collection_test_writes values(?)",UUID.randomUUID()));
        assertEquals(1,sql.queryForObject("select count(*) from collection_test_writes",Integer.class));
        assertThrows(IllegalStateException.class,()->service.finish(next,Outcome.SUCCESS,()->fail("Duplicate write")));
    }
    @Test void unknownAndOrphanExpiredLocksStayUnchanged(){
        UUID run=UUID.randomUUID();sql.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type) values(?,?,'LIVE',now(),'RUNNING','MANUAL')",run,alias);
        assertThrows(IllegalStateException.class,()->service.claim(source,"LIVE"));
        sql.update("update collection_run set status='SUCCESS',finished_at=now() where run_id=?",run);
        sql.update("insert into collection_job_lock values(?,'LIVE',?,now()-interval '1 day')",alias,run);
        assertThrows(IllegalStateException.class,()->service.claim(source,"LIVE"));
        assertEquals(1,sql.queryForObject("select count(*) from collection_job_lock where run_id=?",Integer.class,run));
        assertEquals("SUCCESS",sql.queryForObject("select status from collection_run where run_id=?",String.class,run));
    }
    @Test void failedWritesRollbackAndReleaseLocks(){
        var claim=service.claim(source,"LIVE");
        assertThrows(IllegalArgumentException.class,()->service.finish(claim,Outcome.SUCCESS,()->{sql.update("insert into collection_test_writes values(?)",UUID.randomUUID());throw new IllegalArgumentException("synthetic failure");}));
        assertEquals(0,sql.queryForObject("select count(*) from collection_test_writes",Integer.class));
        service.heartbeat(claim);service.finish(claim,Outcome.FAILED,()->{});assertNotNull(service.claim(source,"LIVE"));
    }
    @Test void databaseFailureRollsBackAndExpiredFinishCannotWrite(){
        var c=service.claim(source,"LIVE");
        assertThrows(RuntimeException.class,()->service.finish(c,Outcome.SUCCESS,()->sql.update("insert into collection_test_writes values(null)")));
        assertEquals("RUNNING",sql.queryForObject("select status from collection_run where run_id=?",String.class,c.run()));
        age(c);assertThrows(IllegalStateException.class,()->service.finish(c,Outcome.SUCCESS,()->fail("Expired write")));
        assertTrue(service.expireReservation(c));
    }
    @Test void immutableSnapshotsAndAssignmentRevocation(){
        var claim=service.claim(source,"LIVE");
        assertThrows(RuntimeException.class,()->sql.update("update collection_source_assignment set quota_scope_id='tamper' where source_id=? and epoch=?",source,epoch));
        assertThrows(RuntimeException.class,()->sql.update("update collection_run set fence_token=fence_token+1 where run_id=?",claim.run()));
        sql.update("update collection_source_assignment set approved=false where source_id=? and epoch=?",source,epoch);
        assertThrows(IllegalStateException.class,()->service.heartbeat(claim));
        assertThrows(IllegalStateException.class,()->service.finish(claim,Outcome.SUCCESS,()->fail("Revoked write")));
        assertThrows(IllegalStateException.class,()->service.expireReservation(claim));
    }
    @Test void approvalUpdateWaitsForOwnedTransactionOnAnotherConnection() throws Exception {
        var claim=service.claim(source,"LIVE");var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var finish=executor.submit(()->service.finish(claim,Outcome.SUCCESS,()->{locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var revoke=executor.submit(()->sql.update("update collection_source_assignment set approved=false where source_id=? and epoch=?",source,epoch));
            try{assertThrows(TimeoutException.class,()->revoke.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}
            finish.get(10,TimeUnit.SECONDS);assertEquals(1,revoke.get(10,TimeUnit.SECONDS));
        }finally{release.countDown();}
    }
    @Test void realTransactionManagerJoinsAndOuterRollbackUndoesClaim(){
        assertThrows(IllegalArgumentException.class,()->tx.execute(status->{service.claim(source,"LIVE");throw new IllegalArgumentException("rollback");}));
        assertEquals(0,sql.queryForObject("select count(*) from collection_run",Integer.class));
        assertNotNull(service.claim(source,"LIVE"));
    }
    @Test void fencingHistoryCannotRegressOrDisappear(){
        var claim=service.claim(source,"LIVE");service.finish(claim,Outcome.FAILED,()->{});
        assertThrows(RuntimeException.class,()->sql.update("update collection_target_lease set fence_token=0 where target_id=?",target));
        assertThrows(RuntimeException.class,()->sql.update("delete from collection_target_lease where target_id=?",target));
        var next=service.claim(source,"LIVE");assertEquals(claim.fence()+1,next.fence());
    }
    @Test void targetUniqueForeignKeysAndSourceAliasAreEnforced(){
        var claim=service.claim(source,"LIVE");
        assertThrows(RuntimeException.class,()->sql.update("""
            insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type,execution_owner,
                assignment_epoch,contract_key,target_id,quota_scope_id,claim_id,fence_token,ownership_phase)
            select ?,source_id,mode,started_at,status,trigger_type,execution_owner,assignment_epoch,contract_key,
                target_id,quota_scope_id,?,fence_token,ownership_phase from collection_run where run_id=?
            """,UUID.randomUUID(),UUID.randomUUID(),claim.run()));
        assertThrows(RuntimeException.class,()->sql.update("insert into collection_source_assignment values(?,?,'KMA_WARNING',?,'MOIS_MESSAGES','q','COMMON_COLLECTION_V1','test',true,true)",source,epoch+100,target));
        assertThrows(RuntimeException.class,()->sql.update("insert into collection_source_assignment values(?,?,'SAFETYDATA_DISASTER_MESSAGE',?,'MOIS_MESSAGES','q','COMMON_COLLECTION_V1','test',true,true)",source,epoch+100,UUID.randomUUID()));
    }
    @Test void forgedClaimCannotHeartbeatFinishOrExpire(){
        var claim=service.claim(source,"LIVE");var forged=new Claim(claim.run(),UUID.randomUUID(),claim.assignment(),claim.mode(),claim.fence());
        assertThrows(IllegalStateException.class,()->service.heartbeat(forged));
        assertThrows(IllegalStateException.class,()->service.finish(forged,Outcome.SUCCESS,()->fail("Forged write")));
        assertThrows(IllegalStateException.class,()->service.expireReservation(forged));service.heartbeat(claim);
    }
    @Test void upgradePreservesLegacySourceRunRawAndRevision(){
        var upgrade=dataSource(database());var legacy=Flyway.configure().dataSource(upgrade).locations("classpath:db/migration").target("2026110201").load();legacy.migrate();
        var db=new JdbcTemplate(upgrade);UUID run=UUID.randomUUID(),raw=UUID.randomUUID();
        UUID oldSource=db.queryForObject("select source_id from data_source where endpoint_key='KMA_EARTHQUAKE'",UUID.class);
        db.update("insert into collection_run values(?,?,'LIVE',now(),null,'RUNNING',0,null,null,'MANUAL',null,null,null,0)",run,oldSource);
        db.update("insert into collection_raw_record values(?,?,'SYNTHETIC',?::jsonb,?,now(),now())",raw,oldSource,"{\"synthetic\":true}","a".repeat(64));
        db.update("insert into collection_raw_revision values(?,1,?::jsonb,?,now())",raw,"{\"synthetic\":true}","a".repeat(64));
        var beforeSources=db.queryForList("select to_jsonb(s)::text value from data_source s order by source_id");
        String beforeRaw=db.queryForObject("select to_jsonb(r)::text from collection_raw_record r where record_id=?",String.class,raw);
        String beforeRevision=db.queryForObject("select to_jsonb(r)::text from collection_raw_revision r where record_id=?",String.class,raw);
        var beforeRun=db.queryForObject("select to_jsonb(r)::text from collection_run r where run_id=?",String.class,run);
        flyway(upgrade).migrate();flyway(upgrade).validate();
        assertEquals(beforeSources,db.queryForList("select (to_jsonb(s)-'collection_assignment_epoch')::text value from data_source s order by source_id"));
        assertEquals(beforeRaw,db.queryForObject("select to_jsonb(r)::text from collection_raw_record r where record_id=?",String.class,raw));
        assertEquals(beforeRevision,db.queryForObject("select to_jsonb(r)::text from collection_raw_revision r where record_id=?",String.class,raw));
        assertEquals(beforeRun,db.queryForObject("select (to_jsonb(r)-array['execution_owner','assignment_epoch','contract_key','target_id','quota_scope_id','claim_id','fence_token','ownership_phase'])::text from collection_run r where run_id=?",String.class,run));
    }
}
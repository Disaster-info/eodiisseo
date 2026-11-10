package com.disasterinfo.eodiisseo.collection.execution;

import com.disasterinfo.eodiisseo.collection.ownership.*;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.*;
import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort.*;
import com.disasterinfo.eodiisseo.collection.execution.CollectionExecutionStore.State;
import com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionProviderPlugin;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.springframework.transaction.TransactionDefinition;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL journal/quota/fencing, synthetic bodies and Fake Transport only. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class CollectionCoordinatorPostgresTest {
    static final LocalDate DAY=LocalDate.of(2026,11,1);
    static final Evidence SYNTHETIC=new Evidence(true,true,true,true,"SYNTHETIC TEST CONTRACT ONLY");
    static final JsonMapper JSON=JsonMapper.builder().build();
    JdbcTemplate admin,sql;String database;DataSourceTransactionManager manager;TransactionTemplate tx;
    JdbcCollectionOwnershipStore ownedStore;CollectionOwnership ownership;JdbcCollectionExecutionStore journal;
    UUID source,alias,target,warning;long epoch;
    CollectionArchivePolicy archive=new CollectionArchivePolicy(Duration.ofHours(1),"SYNTHETIC private test retention/access",Set.of("synthetic-secret-value"));
    @BeforeAll void open(){
        String url=System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        if(!"jdbc:postgresql://127.0.0.1:55433/postgres".equals(url)||!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")))throw new IllegalStateException("Approved disposable audit cluster only");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"eodiisseo_audit",""));
        database="collection2_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+database);
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+database,"eodiisseo_audit","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/collection-executor").load().migrate();
        sql=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        ownedStore=new JdbcCollectionOwnershipStore(sql,manager);ownership=new CollectionOwnership(ownedStore,new CollectionContractRegistry(),Duration.ofSeconds(30));
        journal=new JdbcCollectionExecutionStore(sql,manager);
        source=sql.queryForObject("select source_id from data_source where endpoint_key='SAFETYDATA_DISASTER_MESSAGE'",UUID.class);
        warning=sql.queryForObject("select source_id from data_source where endpoint_key='KMA_WARNING'",UUID.class);
        alias=UUID.randomUUID();sql.update("insert into data_source values(?,'synthetic alias','DISASTER','MOIS_DISASTER_MESSAGES',5,true)",alias);
        for(var key:List.of("MOIS_MESSAGES","KMA_WARNING_LIST","MOIS_WILDFIRE"))sql.update("insert into collection_contract values(?,true)",key);
        System.out.println("STAGE2_POSTGRES_VERSION="+sql.queryForObject("show server_version",String.class));
    }
    @AfterAll void close(){if(database!=null&&database.matches("collection2_[a-f0-9]{32}"))admin.execute("drop database "+database);}
    @BeforeEach void fixture(){
        sql.update("delete from collection_http_archive");sql.update("delete from collection_http_attempt");sql.update("delete from collection_http_execution");
        sql.update("delete from collection_quota_window");sql.update("update collection_target_lease set claim_id=null,run_id=null,lease_until=null");
        sql.update("delete from collection_job_lock");sql.update("delete from collection_run");sql.update("update data_source set collection_assignment_epoch=null");
        epoch++;target=target("MOIS_MESSAGES");assign(source,"SAFETYDATA_DISASTER_MESSAGE",target,"MOIS_MESSAGES");assign(alias,"MOIS_DISASTER_MESSAGES",target,"MOIS_MESSAGES");
        quota(100);
    }
    UUID target(String contract){UUID id=UUID.randomUUID();sql.update("insert into collection_target values(?,?,?,true)",id,"synthetic-"+id,contract);return id;}
    void assign(UUID id,String key,UUID t,String contract){sql.update("insert into collection_source_assignment values(?,?,?,?,?,?,'COMMON_COLLECTION_V1','SYNTHETIC approval',true,true)",id,epoch,key,t,contract,"shared-test-quota");sql.update("update data_source set collection_assignment_epoch=? where source_id=?",epoch,id);}
    void quota(int limit){sql.update("insert into collection_quota_window values('shared-test-quota',now()-interval '1 hour',now()+interval '1 hour',?,0,0,now(),'SYNTHETIC quota approval')",limit);}
    CollectionCoordinator coordinator(OfficialCollectionProviderPlugin.Transport transport){return coordinator(transport,SYNTHETIC,Duration.ofSeconds(3),2,6,4);}
    CollectionCoordinator coordinator(OfficialCollectionProviderPlugin.Transport transport,Evidence evidence,Duration timeout,int attempts,int budget,int pages){
        return new CollectionCoordinator(ownership,journal,List.of(new OfficialCollectionProviderPlugin("MOIS_MESSAGES",transport,evidence),
            new OfficialCollectionProviderPlugin("KMA_WARNING_LIST",transport,evidence),new OfficialCollectionProviderPlugin("MOIS_WILDFIRE",transport,evidence)),
            new CollectionCoordinator.Policy(true,pages,attempts,budget,timeout,Duration.ZERO),archive);
    }
    CollectionCoordinator.Result collect(CollectionCoordinator c){return c.collect(source,DAY,DAY,2);}
    Reply response(String contract,Request request,int total,List<Map<String,Object>> items){
        var header=Map.of("resultCode","00");Object root=contract.equals("MOIS_MESSAGES")?Map.of("header",header,"totalCount",total,"pageNo",request.page(),"body",items):
            Map.of("response",Map.of("header",header,"body",Map.of("totalCount",total,"pageNo",request.page(),"numOfRows",2,"items",Map.of("item",items))));
        return new Reply(200,JSON.writeValueAsBytes(root),Duration.ZERO);
    }
    Map<String,Object> item(String sn){return Map.of("SN",sn,"MSG_CN","SYNTHETIC public test text");}
    long count(String table){return sql.queryForObject("select count(*) from "+table,Long.class);}
    long normal(){return sql.queryForObject("select count(*) from collection_http_archive where classification='NORMAL'",Long.class);}
    long used(){return sql.queryForObject("select reserved from collection_quota_window",Long.class);}
    Claim claim(UUID run){return ownedStore.transaction(t->{var a=t.assignment(source);return new Claim(run,sql.queryForObject("select claim_id from collection_run where run_id=?",UUID.class,run),a,"LIVE",sql.queryForObject("select fence_token from collection_run where run_id=?",Long.class,run));});}
    void await(BooleanSupplier condition)throws Exception {for(int i=0;i<100&&!condition.getAsBoolean();i++)Thread.sleep(20);assertTrue(condition.getAsBoolean());}
    @Test void completePagesOnlyPromoteNewArchiveAndNeverProject(){
        var calls=new AtomicInteger();var result=collect(coordinator((key,r)->{calls.incrementAndGet();return response(key,r,3,r.page()==1?List.of(item("1"),item("2")):List.of(item("3")));}));
        assertEquals(State.COMPLETE,result.state());assertEquals(2,calls.get());assertEquals(2,normal());assertEquals(2,used());
        assertEquals(3,sql.queryForObject("select records_received from collection_run where run_id=?",Integer.class,result.run()));
        assertEquals(0,sql.queryForObject("select records_saved from collection_run where run_id=?",Integer.class,result.run()));
        assertEquals(0,count("collection_raw_record"));assertEquals(0,count("collection_raw_revision"));assertEquals(0,count("source_disaster"));
    }
    @ParameterizedTest @ValueSource(strings={"ZERO_WITH_ROWS","MISSING_PAGE","DUPLICATE","CONTENT_CHANGE","TOTAL_CHANGE","SHORT_MIDDLE","WRONG_PAGE","SCHEMA","PROVIDER_ERROR"})
    void malformedOrUnstablePagesNeverSucceed(String scenario){
        var c=coordinator((key,r)->{
            if(scenario.equals("SCHEMA"))return new Reply(200,"{}".getBytes(),Duration.ZERO);
            if(scenario.equals("PROVIDER_ERROR"))return new Reply(200,"{\"header\":{\"resultCode\":\"99\"}}".getBytes(),Duration.ZERO);
            if(scenario.equals("ZERO_WITH_ROWS"))return response(key,r,0,List.of(item("1")));
            if(scenario.equals("SHORT_MIDDLE"))return response(key,r,3,List.of(item("1")));
            if(scenario.equals("WRONG_PAGE"))return new Reply(200,JSON.writeValueAsBytes(Map.of("header",Map.of("resultCode","00"),"totalCount",1,"pageNo",9,"body",List.of(item("1")))),Duration.ZERO);
            if(r.page()==1)return response(key,r,3,List.of(item("1"),item("2")));
            return switch(scenario){
                case "MISSING_PAGE"->response(key,r,3,List.of());
                case "DUPLICATE"->response(key,r,3,List.of(item("1")));
                case "CONTENT_CHANGE"->response(key,r,3,List.of(Map.of("SN","1","MSG_CN","SYNTHETIC changed")));
                case "TOTAL_CHANGE"->response(key,r,4,List.of(item("3"),item("4")));
                default->throw new AssertionError();
            };
        });
        var result=collect(c);assertEquals(State.INCOMPLETE,result.state());assertEquals(0,normal());assertTrue(count("collection_http_archive")>0);
        assertEquals("FAILED",sql.queryForObject("select status from collection_run where run_id=?",String.class,result.run()));
    }
    @ParameterizedTest @ValueSource(strings={"EMPTY","RANGE","SNAPSHOT","INCREMENTAL"})
    void evidenceNotObservationDeterminesCompletion(String missing){
        var e=new Evidence(!missing.equals("RANGE"),!missing.equals("SNAPSHOT"),!missing.equals("EMPTY"),!missing.equals("INCREMENTAL"),"SYNTHETIC incomplete evidence");
        var c=coordinator((key,r)->response(key,r,missing.equals("EMPTY")?0:1,missing.equals("EMPTY")?List.of():List.of(item("1"))),e,Duration.ofSeconds(3),1,4,3);
        assertEquals(State.INCOMPLETE,collect(c).state());assertEquals(0,normal());
    }
    @ParameterizedTest @ValueSource(ints={429,500,503})
    void definiteErrorRetriesAreJournaledAndCharged(int status){
        var calls=new AtomicInteger();var c=coordinator((key,r)->calls.incrementAndGet()==1?new Reply(status,"SYNTHETIC failure".getBytes(),Duration.ZERO):response(key,r,1,List.of(item("1"))));
        assertEquals(State.COMPLETE,collect(c).state());assertEquals(2,used());assertEquals(2,count("collection_http_archive"));assertEquals(1,normal());
    }
    @Test void exhaustedRetriesAndPerRunBudgetAreNotSuccess(){
        var calls=new AtomicInteger();var c=coordinator((key,r)->{calls.incrementAndGet();return new Reply(503,"SYNTHETIC failure".getBytes(),Duration.ZERO);},SYNTHETIC,Duration.ofSeconds(3),3,1,2);
        assertEquals(State.INCOMPLETE,collect(c).state());assertEquals(1,calls.get());assertEquals(1,used());assertEquals(0,normal());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void sameTargetAliasCannotCallAndHttpHoldsNoRowLocks(boolean useAlias)throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var c=coordinator((key,r)->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1,used());assertEquals(1,sql.queryForObject("select count(*) from collection_http_attempt where state='IN_FLIGHT'",Integer.class));
            tx.executeWithoutResult(s->sql.queryForList("select target_id from collection_target where target_id=? for update nowait",target));
            calls.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){throw new IllegalStateException();}
            return response(key,r,1,List.of(item("1")));});
        try(var executor=Executors.newSingleThreadExecutor()){
            var first=executor.submit(()->collect(c));assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{assertEquals(State.HELD,c.collect(useAlias?alias:source,DAY,DAY,2).state());assertEquals(1,calls.get());}finally{release.countDown();}
            assertEquals(State.COMPLETE,first.get(10,TimeUnit.SECONDS).state());
        }finally{release.countDown();}
    }
    void warning(){assign(warning,"KMA_WARNING",target("KMA_WARNING_LIST"),"KMA_WARNING_LIST");}
    Map<String,Object> warningItem(){return Map.of("tmFc","20261101120000","stnId","108","tmSeq","1");}
    @Test void independentTargetsCanHaveHttpInFlightAtTheSameTime()throws Exception{
        warning();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var c=coordinator((key,r)->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException();}return response(key,r,1,List.of(key.equals("MOIS_MESSAGES")?item("1"):warningItem()));});
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->collect(c));var b=executor.submit(()->c.collect(warning,DAY,DAY,2));
            try{assertTrue(entered.await(5,TimeUnit.SECONDS));}finally{release.countDown();}
            assertEquals(State.COMPLETE,a.get(10,TimeUnit.SECONDS).state());assertEquals(State.COMPLETE,b.get(10,TimeUnit.SECONDS).state());
        }finally{release.countDown();}
    }
    @Test void concurrentTargetsShareQuotaWithoutOverReservation()throws Exception{
        warning();sql.update("update collection_quota_window set request_limit=1");var calls=new AtomicInteger();var c=coordinator((key,r)->{calls.incrementAndGet();return response(key,r,1,List.of(key.equals("MOIS_MESSAGES")?item("1"):warningItem()));});
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->collect(c));var b=executor.submit(()->c.collect(warning,DAY,DAY,2));
            var results=List.of(a.get(10,TimeUnit.SECONDS).state(),b.get(10,TimeUnit.SECONDS).state());
            assertTrue(results.contains(State.COMPLETE));assertTrue(results.contains(State.QUOTA_BLOCKED));assertEquals(1,calls.get());assertEquals(1,used());
        }
    }
    @Test void timeoutRestartAndLateResponseRemainQuarantinedWithoutRefund()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var c=coordinator((key,r)->{calls.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException();}return response(key,r,1,List.of(item("1")));},SYNTHETIC,Duration.ofMillis(150),3,6,3);
        CollectionCoordinator.Result result;
        try{result=collect(c);assertEquals(State.UNKNOWN,result.state());assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertEquals(State.HELD,collect(coordinator((k,r)->{fail("Restart must not repeat request");return null;})).state());
            var claim=claim(result.run());sql.update("update collection_target_lease set lease_until=now()-interval '1 second' where run_id=?",result.run());sql.update("update collection_job_lock set lease_until=now()-interval '1 second' where run_id=?",result.run());
            assertThrows(IllegalStateException.class,()->ownership.expireReservation(claim));
            assertThrows(RuntimeException.class,()->tx.executeWithoutResult(t->sql.update("update collection_run set status='FAILED' where run_id=?",result.run())));
            assertThrows(RuntimeException.class,()->sql.update("update collection_quota_window set reserved=0"));
            assertEquals("RUNNING",sql.queryForObject("select status from collection_run where run_id=?",String.class,result.run()));
        }finally{release.countDown();}
        await(()->count("collection_http_archive")==1);assertEquals(0,normal());assertEquals(1,used());assertEquals(1,calls.get());
        assertEquals("UNKNOWN",sql.queryForObject("select state from collection_http_attempt",String.class));
    }
    @Test void ownershipLossAfterRequestNeverPromotesResponse()throws Exception{
        var c=coordinator((key,r)->{sql.update("update collection_source_assignment set approved=false where source_id=? and epoch=?",source,epoch);return response(key,r,1,List.of(item("1")));});
        assertEquals(State.HELD,collect(c).state());assertEquals(0,normal());assertEquals(1,count("collection_http_archive"));assertEquals(1,used());
    }
    @Test void thrownTransportIsUncertainAndNeverRetried(){
        var calls=new AtomicInteger();var c=coordinator((key,r)->{calls.incrementAndGet();throw new IllegalStateException("SYNTHETIC secret never persisted");});
        assertEquals(State.UNKNOWN,collect(c).state());assertEquals(1,calls.get());assertEquals(1,used());assertEquals(0,count("collection_http_archive"));
    }
    @Test void quotaAndJournalInsertRollbackTogether(){
        sql.execute("create function test_reject_attempt() returns trigger language plpgsql as $$ begin raise exception 'synthetic'; end $$");
        sql.execute("create trigger test_reject_attempt before insert on collection_http_attempt for each row execute function test_reject_attempt()");
        try{assertEquals(State.HELD,collect(coordinator((k,r)->{fail("No HTTP after journal rollback");return null;})).state());assertEquals(0,used());assertEquals(0,count("collection_http_attempt"));}
        finally{sql.execute("drop trigger test_reject_attempt on collection_http_attempt");sql.execute("drop function test_reject_attempt()");}
        UUID run=sql.queryForObject("select run_id from collection_run",UUID.class);var claim=claim(run);
        ownership.finish(claim,Outcome.FAILED,()->journal.terminal(claim,State.FAILED,"SYNTHETIC_ABORT"));
        assertEquals(State.COMPLETE,collect(coordinator((k,r)->response(k,r,1,List.of(item("1"))))).state());
    }
    @Test void promotionFailureRollsBackButKeepsQuarantine(){
        sql.execute("create function test_reject_promotion() returns trigger language plpgsql as $$ begin if NEW.classification='NORMAL' then raise exception 'synthetic'; end if; return NEW; end $$");
        sql.execute("create trigger test_reject_promotion before update on collection_http_archive for each row execute function test_reject_promotion()");
        try{assertEquals(State.HELD,collect(coordinator((k,r)->response(k,r,1,List.of(item("1"))))).state());assertEquals(0,normal());assertEquals(1,count("collection_http_archive"));assertEquals("RUNNING",sql.queryForObject("select status from collection_run",String.class));}
        finally{sql.execute("drop trigger test_reject_promotion on collection_http_archive");sql.execute("drop function test_reject_promotion()");}
    }
    @Test void secretsAndSignedUrlsAreNeverStoredAsBodies(){
        var c=coordinator((key,r)->new Reply(200,"{\"serviceKey\":\"synthetic-secret-value\",\"url\":\"https://example.invalid/?signature=secret\"}".getBytes(),Duration.ZERO));
        assertEquals(State.INCOMPLETE,collect(c).state());assertNull(sql.queryForObject("select raw_body from collection_http_archive",byte[].class));assertEquals(0,normal());
    }
    @Test void middleReceiptFailureKeepsEarlierArchiveAndBlocksReplay(){
        sql.execute("create function test_reject_receipt() returns trigger language plpgsql as $$ begin if (select page_no from collection_http_attempt where attempt_id=NEW.attempt_id)=2 then raise exception 'synthetic'; end if; return NEW; end $$");
        sql.execute("create trigger test_reject_receipt before insert on collection_http_archive for each row execute function test_reject_receipt()");
        var calls=new AtomicInteger();
        try{
            var c=coordinator((key,r)->{calls.incrementAndGet();return response(key,r,3,r.page()==1?List.of(item("1"),item("2")):List.of(item("3")));});
            assertEquals(State.UNKNOWN,collect(c).state());assertEquals(2,calls.get());assertEquals(2,used());assertEquals(1,count("collection_http_archive"));assertEquals(0,normal());
            assertEquals(State.HELD,collect(coordinator((k,r)->{fail("Cannot replay uncertain receipt");return null;})).state());
        }finally{sql.execute("drop trigger test_reject_receipt on collection_http_archive");sql.execute("drop function test_reject_receipt()");}
    }
    @Test void overlappingQuotaApprovalsAreHeldWithoutRequests(){
        sql.update("insert into collection_quota_window values('shared-test-quota',now()-interval '2 hours',now()+interval '2 hours',10,0,0,now(),'SYNTHETIC conflicting approval')");
        assertEquals(State.HELD,collect(coordinator((k,r)->{fail("Ambiguous quota must not call");return null;})).state());
        assertEquals(0,count("collection_http_attempt"));assertEquals(0,sql.queryForObject("select sum(reserved) from collection_quota_window",Long.class));
    }
    @Test void minimumIntervalIsSharedAndDoesNotCreateFalseCoverage(){
        sql.update("delete from collection_quota_window");
        sql.update("insert into collection_quota_window values('shared-test-quota',now()-interval '1 hour',now()+interval '1 hour',10,0,10000,now(),'SYNTHETIC interval approval')");
        var calls=new AtomicInteger();var c=coordinator((k,r)->{calls.incrementAndGet();return response(k,r,3,List.of(item("1"),item("2")));});
        assertEquals(State.QUOTA_BLOCKED,collect(c).state());assertEquals(1,calls.get());assertEquals(1,used());assertEquals(0,normal());
    }
    @Test void optionalMigrationUpgradesStage16WithoutRewritingLegacyData(){
        String upgrade="collection2_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+upgrade);
        try{
            var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+upgrade,"eodiisseo_audit","");
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            var db=new JdbcTemplate(ds);UUID oldSource=db.queryForObject("select source_id from data_source where endpoint_key='KMA_EARTHQUAKE'",UUID.class),run=UUID.randomUUID(),raw=UUID.randomUUID();
            db.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type) values(?,?,'LIVE',now(),'RUNNING','MANUAL')",run,oldSource);
            db.update("insert into collection_raw_record values(?,?,'SYNTHETIC',?::jsonb,?,now(),now())",raw,oldSource,"{\"synthetic\":true}","a".repeat(64));
            db.update("insert into collection_raw_revision values(?,1,?::jsonb,?,now())",raw,"{\"synthetic\":true}","a".repeat(64));
            var sources=db.queryForList("select to_jsonb(s)::text value from data_source s order by source_id");
            var beforeRun=db.queryForObject("select to_jsonb(r)::text from collection_run r where run_id=?",String.class,run);
            var beforeRaw=db.queryForObject("select to_jsonb(r)::text from collection_raw_record r where record_id=?",String.class,raw);
            var beforeRevision=db.queryForObject("select to_jsonb(r)::text from collection_raw_revision r where record_id=?",String.class,raw);
            var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/collection-executor").load();
            assertEquals(1,flyway.migrate().migrationsExecuted);flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
            assertEquals(sources,db.queryForList("select to_jsonb(s)::text value from data_source s order by source_id"));
            assertEquals(beforeRun,db.queryForObject("select to_jsonb(r)::text from collection_run r where run_id=?",String.class,run));
            assertEquals(beforeRaw,db.queryForObject("select to_jsonb(r)::text from collection_raw_record r where record_id=?",String.class,raw));
            assertEquals(beforeRevision,db.queryForObject("select to_jsonb(r)::text from collection_raw_revision r where record_id=?",String.class,raw));
        }finally{admin.execute("drop database "+upgrade);}
    }
    @Test void conflictingMessageAliasAssignmentsAndWildfireAreBlocked(){
        sql.update("update data_source set collection_assignment_epoch=null where source_id=?",alias);epoch++;assign(alias,"MOIS_DISASTER_MESSAGES",target("MOIS_MESSAGES"),"MOIS_MESSAGES");
        assertEquals(State.HELD,collect(coordinator((k,r)->{fail("Conflicting aliases must not call");return null;})).state());
        UUID fire=UUID.randomUUID();sql.update("insert into data_source values(?,'synthetic fire','DISASTER','SAFETYDATA_WILDFIRE',5,true)",fire);assign(fire,"SAFETYDATA_WILDFIRE",target("MOIS_WILDFIRE"),"MOIS_WILDFIRE");
        var result=coordinator((k,r)->{fail("Unverified fire contract must not call");return null;}).collect(fire,DAY,DAY,2);
        assertEquals(State.FAILED,result.state());assertEquals(0,used());
    }
}
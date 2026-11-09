package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.collection.client.CollectionClient;
import com.disasterinfo.eodiisseo.collection.repository.CollectionRepository;
import com.disasterinfo.eodiisseo.collection.service.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Owned safeguards plus an explicit unresolved shared-expiry reproduction. Synthetic transports only. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class CollectionOwnershipIntegrationTest {
    JdbcTemplate admin,sql;
    OwnedDatabase db;
    DataSourceTransactionManager manager;
    TransactionTemplate tx;
    CollectionRepository shared;
    String name;
    Clock clock=Clock.systemUTC();
    static final UUID SOURCE=UUID.fromString("20000000-0000-0000-0000-000000000003");
    @BeforeAll void open(){
        String base=System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")) ||
            !"jdbc:postgresql://127.0.0.1:55433/postgres".equals(base))throw new IllegalStateException("Disposable audit cluster only");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,"eodiisseo_audit",""));
        name="owned_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+name);
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+name,"eodiisseo_audit","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime").load().migrate();
        sql=new JdbcTemplate(ds);db=new OwnedDatabase(sql);manager=new DataSourceTransactionManager(ds);
        tx=new TransactionTemplate(manager);shared=new CollectionRepository(db);
    }
    @AfterAll void close(){
        if(admin!=null && name!=null && name.matches("owned_[a-f0-9]{32}"))admin.execute("DROP DATABASE "+name+" WITH (FORCE)");
    }
    @BeforeEach void reset(){
        sql.update("delete from collection_job_lock");
        sql.update("delete from collection_run");
        sql.update("delete from owned_collection_cursor");
    }
    OfficialCollectionService owned(){
        return new OfficialCollectionService(db,mock(OfficialRecordNormalizer.class),manager,clock);
    }
    OfficialProviderClient empty(OfficialProviderClient.Provider provider,Runnable beforeReply){
        return new OfficialProviderClient(provider,(uri,timeout)->{
            beforeReply.run();
            String body=provider==OfficialProviderClient.Provider.WILDFIRE_MESSAGES?
                "{\"header\":{\"resultCode\":\"00\"},\"totalCount\":0,\"body\":[]}":
                "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0,\"items\":\"\"}}}";
            return new OfficialHttp.Reply(200,body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
    }
    CollectionService sharedWorker(AtomicInteger calls){
        var client=new CollectionClient(){
            public Set<String> configuredSources(){return Set.of("KMA_EARTHQUAKE","KMA_WARNING","SAFETYDATA_DISASTER_MESSAGE");}
            public List<com.disasterinfo.eodiisseo.collection.domain.CollectedRecord> fetch(String source,Runnable heartbeat){
                calls.incrementAndGet();heartbeat.run();return List.of();
            }
        };
        var writer=new CollectionRecordWriter(db,JsonMapper.builder().build(),
            new com.disasterinfo.eodiisseo.disaster.service.DisasterService(db));
        return new CollectionService(shared,client,writer,manager,clock,true);
    }
    LocalDate day(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).minusDays(1);}
    @Test void directlyExecutingOwnedRunIsNotAnUnclaimedSharedJob(){
        AtomicInteger sharedCalls=new AtomicInteger();
        var worker=sharedWorker(sharedCalls);
        var result=owned().collect(SOURCE,empty(OfficialProviderClient.Provider.EARTHQUAKE,()->{
            var selected=tx.execute(s->{shared.gate();return shared.pending();});
            assertThat(selected).isNull();
            UUID run=sql.queryForObject("select run_id from collection_job_lock where source_id=?",UUID.class,SOURCE);
            worker.workOne();
            assertThat(sql.queryForObject("select status from collection_run where run_id=?",String.class,run)).isEqualTo("RUNNING");
            assertThat(sql.queryForObject("select count(*) from collection_job_lock where run_id=?",Long.class,run)).isEqualTo(1);
        }),"synthetic",day(),day(),"owned-disaster-development");
        assertThat(sharedCalls.get()).isZero();
        assertThat(result.status()).isEqualTo("SUCCESS");
    }
    @Test void sameSourceActiveLeaseStopsTheOtherCollectorFromStarting(){
        var worker=sharedWorker(new AtomicInteger());
        owned().collect(SOURCE,empty(OfficialProviderClient.Provider.EARTHQUAKE,()->{
            assertThatThrownBy(()->worker.request(null,"KMA_EARTHQUAKE","LIVE",false))
                .isInstanceOfSatisfying(com.disasterinfo.eodiisseo.auth.exception.ApiProblem.class,
                    e->assertThat(e.code()).isEqualTo("COLLECTION_ALREADY_RUNNING"));
        }),"synthetic",day(),day(),"owned-disaster-development");
        sql.update("update owned_collection_cursor set next_attempt_at='-infinity' where source_id=?",SOURCE);
        worker.request(null,"KMA_EARTHQUAKE","LIVE",false);
        AtomicInteger ownedCalls=new AtomicInteger();
        assertThat(owned().collect(SOURCE,empty(OfficialProviderClient.Provider.EARTHQUAKE,ownedCalls::incrementAndGet),
            "synthetic",day(),day(),"owned-disaster-development").status()).isEqualTo("BUSY_OR_RATE_LIMITED");
        assertThat(ownedCalls.get()).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=OfficialProviderClient.Provider.class,names={"HEAVY_RAIN","WILDFIRE_MESSAGES"})
    void activeProviderAliasHoldsOwnedCollectionWithoutASecondCall(OfficialProviderClient.Provider provider){
        UUID alias=UUID.randomUUID();
        sql.update("insert into data_source values(?,?,'DISASTER',?,5,true)",alias,"SYNTHETIC ALIAS",provider.endpointKey);
        try{
            AtomicInteger sharedCalls=new AtomicInteger(),ownedCalls=new AtomicInteger();
            var worker=sharedWorker(sharedCalls);
            worker.request(null,provider==OfficialProviderClient.Provider.HEAVY_RAIN?"KMA_WARNING":"SAFETYDATA_DISASTER_MESSAGE","LIVE",false);
            assertThat(owned().collect(alias,empty(provider,()->{
                ownedCalls.incrementAndGet();worker.workOne();
            }),"synthetic",day(),day(),"owned-disaster-development").status()).isEqualTo("PROVIDER_OWNERSHIP_CONFLICT");
            worker.workOne();
            assertThat(sharedCalls.get()).isEqualTo(1);
            assertThat(ownedCalls.get()).isZero();
        }finally{
            sql.update("delete from collection_job_lock");sql.update("delete from collection_run");
            sql.update("delete from owned_collection_cursor");sql.update("delete from data_source where source_id=?",alias);
        }
    }
    @Test void reproducesSharedExpiryMutatingAnOwnedLeaseAndRestartSelection(){
        owned().collect(SOURCE,empty(OfficialProviderClient.Provider.EARTHQUAKE,()->{
            UUID run=sql.queryForObject("select run_id from collection_job_lock where source_id=?",UUID.class,SOURCE);
            sql.update("update collection_job_lock set lease_until=? where run_id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(1)),run);
            var restarted=new CollectionRepository(new OwnedDatabase(sql));
            tx.executeWithoutResult(s->{restarted.gate();restarted.expire(clock.instant());});
            assertThat(sql.queryForObject("select error_code from collection_run where run_id=?",String.class,run)).isEqualTo("COLLECTION_LEASE_EXPIRED");
            assertThat(sql.queryForObject("select count(*) from collection_job_lock where run_id=?",Long.class,run)).isZero();
        }),"synthetic",day(),day(),"owned-disaster-development");
    }
    @Test void claimTransactionRollsBackItsRunAndLockOnFailure(){
        long before=sql.queryForObject("select count(*) from collection_run",Long.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->{
            shared.gate();shared.enqueue(UUID.randomUUID(),SOURCE,"LIVE",null,null,clock.instant(),"SCHEDULED");
            shared.enqueue(UUID.randomUUID(),SOURCE,"LIVE",null,null,clock.instant(),"SCHEDULED");
        })).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(sql.queryForObject("select count(*) from collection_run",Long.class)).isEqualTo(before);
        assertThat(sql.queryForObject("select count(*) from collection_job_lock",Long.class)).isZero();
    }

    @Test void expiredUnattributedLeaseIsHeldWithoutCallsOrStateChanges(){
        UUID run=UUID.randomUUID();
        tx.executeWithoutResult(s->{shared.gate();shared.enqueue(run,SOURCE,"LIVE",null,null,clock.instant(),"SCHEDULED");});
        sql.update("update collection_job_lock set lease_until=? where run_id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(1)),run);
        var before=sql.queryForMap("select * from collection_run where run_id=?",run);
        var lease=sql.queryForMap("select * from collection_job_lock where run_id=?",run);
        AtomicInteger calls=new AtomicInteger();
        var restarted=new OfficialCollectionService(new OwnedDatabase(sql),mock(OfficialRecordNormalizer.class),manager,clock);
        assertThat(restarted.collect(SOURCE,empty(OfficialProviderClient.Provider.EARTHQUAKE,calls::incrementAndGet),
            "synthetic",day(),day(),"owned-disaster-development").status()).isEqualTo("LEASE_OWNERSHIP_UNVERIFIED");
        assertThat(calls.get()).isZero();
        assertThat(sql.queryForMap("select * from collection_run where run_id=?",run)).isEqualTo(before);
        assertThat(sql.queryForMap("select * from collection_job_lock where run_id=?",run)).isEqualTo(lease);
        assertThat(sql.queryForObject("select count(*) from owned_collection_cursor",Long.class)).isZero();
    }
    @Test void simultaneousFirstClaimsUseTheSharedGateAndCreateOnlyOneRun() throws Exception {
        CyclicBarrier start=new CyclicBarrier(2);
        CountDownLatch sharedAttempt=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)){
            var team=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                try{sharedWorker(new AtomicInteger()).request(null,"KMA_EARTHQUAKE","LIVE",false);return "ACCEPTED";}
                catch(com.disasterinfo.eodiisseo.auth.exception.ApiProblem e){assertThat(e.code()).isEqualTo("COLLECTION_ALREADY_RUNNING");return "CONFLICT";}
                finally{sharedAttempt.countDown();}
            });
            var direct=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return owned().collect(SOURCE,empty(OfficialProviderClient.Provider.EARTHQUAKE,()->{
                    calls.incrementAndGet();
                    try{assertThat(sharedAttempt.await(10,TimeUnit.SECONDS)).isTrue();}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                }),"synthetic",day(),day(),"owned-disaster-development").status();
            });
            String teamStatus=team.get(20,TimeUnit.SECONDS),directStatus=direct.get(20,TimeUnit.SECONDS);
            if(teamStatus.equals("ACCEPTED")){assertThat(directStatus).isEqualTo("BUSY_OR_RATE_LIMITED");assertThat(calls.get()).isZero();}
            else {assertThat(directStatus).isEqualTo("SUCCESS");assertThat(calls.get()).isEqualTo(1);}
            assertThat(sql.queryForObject("select count(*) from collection_run",Long.class)).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from collection_job_lock",Long.class)).isEqualTo(teamStatus.equals("ACCEPTED")?1:0);
        }
    }
    @Test void exactDuplicateEndpointIsAlreadyRejectedByTheCanonicalUniqueContract(){
        assertThatThrownBy(()->sql.update("insert into data_source values(?,?,'DISASTER','KMA_EARTHQUAKE',5,true)",
            UUID.randomUUID(),"SYNTHETIC DUPLICATE")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
    @Test void anExclusiveMessageSourceStillCollectsWithoutActivatingTheSharedAlias(){
        UUID source=UUID.randomUUID();
        sql.update("insert into data_source values(?,?,'DISASTER','MOIS_DISASTER_MESSAGES',5,true)",source,"SYNTHETIC EXCLUSIVE");
        sql.update("update data_source set active=false where endpoint_key='SAFETYDATA_DISASTER_MESSAGE'");
        try{
            AtomicInteger calls=new AtomicInteger();
            assertThat(owned().collect(source,empty(OfficialProviderClient.Provider.WILDFIRE_MESSAGES,calls::incrementAndGet),
                "synthetic",day(),day(),"owned-disaster-development").status()).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from owned_collection_coverage",Long.class)).isZero();
        }finally{
            sql.update("delete from collection_job_lock");sql.update("delete from collection_run");
            sql.update("delete from owned_collection_cursor");sql.update("delete from data_source where source_id=?",source);
            sql.update("update data_source set active=true where endpoint_key='SAFETYDATA_DISASTER_MESSAGE'");
        }
    }
    @Test void persistenceFailureRollsBackOwnedPagesItemsAndCoverageButRecordsFailure(){
        var normalizer=mock(OfficialRecordNormalizer.class);
        when(normalizer.normalize(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("SYNTHETIC FAILURE"));
        var service=new OfficialCollectionService(db,normalizer,manager,clock);
        var client=new OfficialProviderClient(OfficialProviderClient.Provider.EARTHQUAKE,(uri,timeout)->{
            var row=Map.of("stnId","108","tmFc",day().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)+"1000","tmSeq","1");
            return new OfficialHttp.Reply(200,JsonMapper.builder().build().writeValueAsBytes(
                Map.of("response",Map.of("header",Map.of("resultCode","00"),"body",Map.of("totalCount",1,"items",Map.of("item",List.of(row)))))));
        });
        assertThat(service.collect(SOURCE,client,"synthetic",day(),day(),"owned-disaster-development").status()).isEqualTo("PERSISTENCE_OR_NORMALIZATION");
        assertThat(sql.queryForObject("select status from collection_run",String.class)).isEqualTo("FAILED");
        assertThat(sql.queryForObject("select count(*) from owned_collection_page",Long.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from owned_collection_item",Long.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from owned_collection_coverage",Long.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from collection_job_lock",Long.class)).isZero();
    }

    @Test void activatingAnAliasDuringFetchDoesNotCommitAFalseSuccess(){
        UUID source=UUID.randomUUID();
        sql.update("insert into data_source values(?,?,'DISASTER','MOIS_DISASTER_MESSAGES',5,true)",source,"SYNTHETIC CONFIG CHANGE");
        sql.update("update data_source set active=false where endpoint_key='SAFETYDATA_DISASTER_MESSAGE'");
        try{
            AtomicInteger calls=new AtomicInteger();
            assertThat(owned().collect(source,empty(OfficialProviderClient.Provider.WILDFIRE_MESSAGES,()->{
                calls.incrementAndGet();
                sql.update("update data_source set active=true where endpoint_key='SAFETYDATA_DISASTER_MESSAGE'");
            }),"synthetic",day(),day(),"owned-disaster-development").status()).isEqualTo("PROVIDER_OWNERSHIP_CONFLICT");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(sql.queryForObject("select status from collection_run where source_id=?",String.class,source)).isEqualTo("FAILED");
            assertThat(sql.queryForObject("select count(*) from owned_collection_page",Long.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from collection_job_lock",Long.class)).isZero();
        }finally{
            sql.update("delete from collection_job_lock");sql.update("delete from collection_run");
            sql.update("delete from owned_collection_cursor");sql.update("delete from data_source where source_id=?",source);
            sql.update("update data_source set active=true where endpoint_key='SAFETYDATA_DISASTER_MESSAGE'");
        }
    }
}

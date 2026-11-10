package com.disasterinfo.eodiisseo.disaster.projection;

import com.disasterinfo.eodiisseo.collection.execution.*;
import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort.*;
import com.disasterinfo.eodiisseo.collection.execution.CollectionExecutionStore.State;
import com.disasterinfo.eodiisseo.collection.ownership.*;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim;
import com.disasterinfo.eodiisseo.disaster.collection.*;
import com.disasterinfo.eodiisseo.disaster.service.*;
import com.disasterinfo.eodiisseo.disaster.port.*;
import com.disasterinfo.eodiisseo.disaster.adapter.VerifiedWarningImpactReader;
import audit.collection.fixture.ProjectionTxProbe;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.collection.service.CollectionRecordWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

/** Real default Spring JPA transaction manager + proxied existing services + PostgreSQL. No external I/O. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Timeout(90)
class OfficialCollectionProjectionPostgresTest {
    static final LocalDate DAY=LocalDate.of(2026,11,1);
    static final JsonMapper JSON=JsonMapper.builder().build();
    JdbcTemplate admin,sql;String database;AnnotationConfigApplicationContext app;JpaTransactionManager manager;
    CollectionOwnership ownership;JdbcCollectionExecutionStore store;OfficialCollectionProjection projection;Fixture state;
    UUID quake,rain,message,alias,shelter;long epoch;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @Profile("collection-projection-audit-only") @EnableTransactionManagement
    static class Config {
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource){return new JdbcTemplate(dataSource);}
        @Bean OwnedDatabase ownedDatabase(JdbcTemplate jdbc){return new OwnedDatabase(jdbc);}
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource){
            var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(dataSource);f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setPackagesToScan("audit.collection.fixture");f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate"));return f;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory){return new JpaTransactionManager(factory);}
        @Bean Fixture fixture(){return new Fixture();}
        @Bean DisasterService disasterService(OwnedDatabase db){return new DisasterService(db);}
        @Bean DisasterRuntimeEvents events(OwnedDatabase db){return new DisasterRuntimeEvents(db);}
        @Bean VerifiedIncidentService incidents(OwnedDatabase db,DisasterService service,DisasterRuntimeEvents events,Fixture fixture){return new VerifiedIncidentService(db,service,events,Optional.of(fixture));}
        @Bean VerifiedWarningImpactReader impactReader(OwnedDatabase db,Fixture fixture){return new VerifiedWarningImpactReader(db,Optional.of(fixture));}
        @Bean OfficialImpactAreaSynchronizer impactSync(OwnedDatabase db,VerifiedWarningImpactReader reader){return new OfficialImpactAreaSynchronizer(db,reader);}
        @Bean OfficialRecordNormalizer normalizer(OwnedDatabase db,DisasterService service,DisasterRuntimeEvents events,VerifiedIncidentService incidents){return new OfficialRecordNormalizer(db,service,events,incidents);}
        @Bean CollectionRecordWriter shelterWriter(OwnedDatabase db,DisasterService service){return new CollectionRecordWriter(db,JSON,service);}
    }
    static class Fixture implements ProjectionAdmissionPort,VerifiedIncidentLinkPort,OfficialWarningAreaMappingPort {
        final java.util.concurrent.atomic.AtomicInteger httpCalls=new java.util.concurrent.atomic.AtomicInteger();
        final Map<String,Link> links=new ConcurrentHashMap<>();final Map<String,Mapping> mappings=new ConcurrentHashMap<>();
        Runnable duringLink=()->{};boolean admitted=true,coverage=true,empty=false;String version="SYNTHETIC-domain-v1";UUID canonical;LocalDate initial=DAY;
        final Instant until=Instant.parse("2099-01-01T00:00:00Z");
        public Optional<Grant> resolve(Claim c,LocalDate from,LocalDate to,Instant now){return admitted?Optional.of(new Grant(c.assignment().target(),c.assignment().contract(),canonical,version,"SYNTHETIC independent domain evidence - NOT HTTP COMPLETE",initial,until,coverage,empty,true)):Optional.empty();}
        public Optional<Link> resolve(UUID source,OfficialProviderClient.Provider provider,String announcement,String hash){duringLink.run();return Optional.ofNullable(links.get(source+":"+announcement+":"+hash));}
        public Optional<Mapping> resolve(String zone,Instant issued,Instant asOf){return Optional.ofNullable(mappings.get(zone));}
    }
    @BeforeAll void open(){
        String url=System.getenv("EODIISSEO_AUDIT_JDBC_URL");if(!"jdbc:postgresql://127.0.0.1:55433/postgres".equals(url)||!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")))throw new IllegalStateException("Approved disposable audit only");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"eodiisseo_audit",""));database="collection3_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+database);
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+database,"eodiisseo_audit","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime","classpath:db/collection-executor","classpath:db/collection-projection").load().migrate();
        sql=new JdbcTemplate(ds);sql.execute("create table projection_tx_probe(id uuid primary key)");
        sql.update("insert into data_source values(?,'SYNTHETIC rain','DISASTER','KMA_HEAVY_RAIN',5,true)",UUID.randomUUID());
        sql.update("insert into data_source values(?,'SYNTHETIC alias','DISASTER','MOIS_DISASTER_MESSAGES',5,true)",UUID.randomUUID());
        quake=source("KMA_EARTHQUAKE");rain=source("KMA_HEAVY_RAIN");message=source("SAFETYDATA_DISASTER_MESSAGE");alias=source("MOIS_DISASTER_MESSAGES");shelter=source("SAFETYDATA_EARTHQUAKE_SHELTER");
        for(String key:List.of("KMA_PORTAL_EQK_MSG","KMA_RAIN_CODES","MOIS_MESSAGES","MOIS_SHELTERS"))sql.update("insert into collection_contract values(?,true)",key);
        sql.update("insert into admin_area(area_code,name,level,active) values('SYNTHETIC_A','SYNTHETIC A','SIGUNGU',true),('SYNTHETIC_B','SYNTHETIC B','SIGUNGU',true)");
        app=new AnnotationConfigApplicationContext();app.getEnvironment().setActiveProfiles("collection-projection-audit-only");app.registerBean("dataSource",DataSource.class,()->ds);app.register(Config.class);app.refresh();
        manager=app.getBean("transactionManager",JpaTransactionManager.class);state=app.getBean(Fixture.class);
        ownership=new CollectionOwnership(new JdbcCollectionOwnershipStore(sql,manager),new CollectionContractRegistry(),Duration.ofSeconds(30));store=new JdbcCollectionExecutionStore(sql,manager);
        projection=new OfficialCollectionProjection(sql,app.getBean(OwnedDatabase.class),manager,app.getBean(OfficialRecordNormalizer.class),app.getBean(OfficialImpactAreaSynchronizer.class),app.getBean(VerifiedWarningImpactReader.class),app.getBean(CollectionRecordWriter.class),state);
        System.out.println("STAGE3_POSTGRES_VERSION="+sql.queryForObject("show server_version",String.class)+" manager="+manager.getClass().getSimpleName());
    }
    UUID source(String endpoint){return sql.queryForObject("select source_id from data_source where endpoint_key=?",UUID.class,endpoint);}
    @AfterAll void close(){if(app!=null)app.close();if(database!=null&&database.matches("collection3_[a-f0-9]{32}"))admin.execute("drop database "+database);}
    @BeforeEach void fixture(){
        sql.execute("truncate collection_run,disaster,collection_raw_record,projection_tx_probe,shelter cascade");sql.update("delete from collection_quota_window");sql.update("update data_source set collection_assignment_epoch=null");
        epoch++;state.httpCalls.set(0);state.duringLink=()->{};state.links.clear();state.mappings.clear();state.admitted=true;state.coverage=true;state.empty=false;state.version="SYNTHETIC-domain-v1";state.initial=DAY;
        sql.update("insert into collection_quota_window values('SYNTHETIC-quota',now()-interval '1 hour',now()+interval '1 hour',100,0,0,now(),'SYNTHETIC approval')");
    }
    void assign(UUID id,String contract){
        String key=sql.queryForObject("select endpoint_key from data_source where source_id=?",String.class,id);UUID target=UUID.randomUUID();
        sql.update("insert into collection_target values(?,?,?,true)",target,"SYNTHETIC-"+target,contract);assign(id,key,target,contract);state.canonical=id;
    }
    void assign(UUID id,String key,UUID target,String contract){sql.update("insert into collection_source_assignment values(?,?,?,?,?,'SYNTHETIC-quota','COMMON_COLLECTION_V1','SYNTHETIC assignment',true,true)",id,epoch,key,target,contract);sql.update("update data_source set collection_assignment_epoch=? where source_id=?",epoch,id);}
    CollectionCoordinator coordinator(String contract,List<Map<String,Object>> rows,CollectionProjectionPort adapter){
        var provider=new OfficialCollectionProviderPlugin(contract,(key,request)->{
            state.httpCalls.incrementAndGet();
            Object envelope=key.startsWith("MOIS_")?Map.of("header",Map.of("resultCode","00"),"body",rows,"totalCount",rows.size()):Map.of("response",Map.of("header",Map.of("resultCode","00"),"body",Map.of("totalCount",rows.size(),"items",Map.of("item",rows))));
            return new Reply(200,JSON.writeValueAsBytes(envelope),Duration.ZERO);
        },new Evidence(true,true,true,true,"SYNTHETIC HTTP evidence - NOT domain admission"));
        return new CollectionCoordinator(ownership,store,List.of(provider),new CollectionCoordinator.Policy(true,2,1,2,Duration.ofSeconds(5),Duration.ZERO),new CollectionArchivePolicy(Duration.ofHours(1),"SYNTHETIC private retention",Set.of()),adapter);
    }
    CollectionCoordinator.Result collect(UUID id,String contract,Map<String,Object> row){return coordinator(contract,List.of(row),projection).collect(id,DAY,DAY,10);}
    Map<String,Object> quake(String seq,String issued,String location){return new LinkedHashMap<>(Map.of("stnId","108","tmFc",issued,"tmSeq",seq,"fcTp","3","tmEqk","20261101120000","lat","37.1","lon","127.1","loc",location));}
    void allow(UUID canonical,OfficialProviderClient.Provider provider,Map<String,Object> row,String incident,long sequence){
        String original=OfficialProviderClient.canonical(row),announcement=OfficialRecordNormalizer.identity(provider,row);
        Instant issued=provider==OfficialProviderClient.Provider.EARTHQUAKE?OfficialRecordNormalizer.time(row.get("tmFc")):Instant.parse("2026-11-01T03:01:00Z");
        var link=new VerifiedIncidentLinkPort.Link(canonical,provider,announcement,JdbcCollectionRawStore.hash(original),incident,sequence,issued,Instant.parse("2026-11-01T03:00:00Z"),VerifiedIncidentLinkPort.State.OCCURRED,null,URI.create("https://www.kma.go.kr/SYNTHETIC-test-only"),"SYNTHETIC official contract fixture, not real proof");
        state.links.put(canonical+":"+announcement+":"+JdbcCollectionRawStore.hash(original),link);
    }
    long count(String table){return sql.queryForObject("select count(*) from "+table,Long.class);}
    long event(String kind){return sql.queryForObject("select count(*) from disaster_runtime_outbox where event_kind=?",Long.class,kind);}
    @Test void sourceToIncidentRevisionOutboxReceiptCoverageAndCursor(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","SYNTHETIC first");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"synthetic-quake",1);
        var result=collect(quake,"KMA_PORTAL_EQK_MSG",row);assertEquals(State.COMPLETE,result.state());assertEquals(1,event("NEW"));assertEquals(1,event("REVISION"));
        assertEquals(1,count("collection_raw_record"));assertEquals(1,count("collection_raw_revision"));assertEquals(1,count("collection_projection_receipt"));assertEquals(1,count("collection_domain_coverage"));
        assertEquals(DAY,sql.queryForObject("select through_date from collection_domain_cursor",java.sql.Date.class).toLocalDate());
        assertEquals("DOMAIN_APPLIED",sql.queryForObject("select domain_phase from collection_http_execution where run_id=?",String.class,result.run()));
        assertEquals(1,sql.queryForObject("select count(*) from collection_projection_receipt p join source_disaster_revision r on p.source_revision_id=r.revision_id where p.official_hash=r.content_hash",Integer.class));
    }
    @Test void correctionDuplicateAndOlderAnnouncementDoNotDuplicateOrRewind(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var first=quake("1","20261101120100","SYNTHETIC first");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,first,"q",1);
        assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",first).state());assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",first).state());
        assertEquals(1,event("NEW"));assertEquals(1,event("REVISION"));assertEquals(1,count("collection_raw_revision"));
        var corrected=quake("2","20261101120200","SYNTHETIC corrected");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,corrected,"q",2);
        assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",corrected).state());assertEquals(2,event("REVISION"));assertEquals(2,count("source_disaster_revision"));
        assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",first).state());assertEquals(2,event("REVISION"));
        assertEquals(2L,sql.queryForObject("select official_sequence from owned_incident_head",Long.class));
    }
    @Test void invalidCoordinatesDoNotUpdateHeadOrOldRevision(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var first=quake("1","20261101120100","first");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,first,"q",1);collect(quake,"KMA_PORTAL_EQK_MSG",first);
        var invalid=quake("2","20261101120200","invalid");invalid.put("lat","999");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,invalid,"q",2);
        assertEquals(State.FAILED,collect(quake,"KMA_PORTAL_EQK_MSG",invalid).state());assertEquals(1,count("source_disaster_revision"));assertEquals(1,event("REVISION"));assertEquals(1L,sql.queryForObject("select official_sequence from owned_incident_head",Long.class));
        assertEquals(1,count("collection_raw_record"));assertEquals(1,count("collection_domain_coverage"));
    }
    @Test void unlinkedThenNewOfficialEvidenceCanBeReprocessed(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","unlinked");
        assertEquals(State.FAILED,collect(quake,"KMA_PORTAL_EQK_MSG",row).state());assertEquals(0,count("disaster"));assertEquals(0,count("collection_domain_cursor"));
        assertEquals("EARTHQUAKE_UNLINKED",sql.queryForObject("select result from collection_projection_receipt",String.class));
        allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",row).state());assertEquals(1,event("NEW"));
    }
    @Test void httpSyntheticEvidenceDoesNotAuthorizeDomain(){
        assign(quake,"KMA_PORTAL_EQK_MSG");state.admitted=false;var row=quake("1","20261101120100","unapproved");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);
        assertEquals(State.FAILED,collect(quake,"KMA_PORTAL_EQK_MSG",row).state());assertEquals(0,count("collection_raw_record"));assertEquals(0,event("NEW"));assertEquals(0,count("collection_domain_coverage"));
        assertEquals(0,sql.queryForObject("select count(*) from collection_http_archive where classification='NORMAL'",Integer.class));
    }
    @Test void entireBatchRollsBackWhenOneAnnouncementIsUnlinked(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var first=quake("1","20261101120100","valid");var second=quake("2","20261101120200","unlinked");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,first,"q",1);
        var result=coordinator("KMA_PORTAL_EQK_MSG",List.of(first,second),projection).collect(quake,DAY,DAY,10);
        assertEquals(State.FAILED,result.state());assertEquals(0,count("disaster"));assertEquals(0,count("collection_raw_record"));assertEquals(0,count("disaster_runtime_outbox"));assertEquals(2,count("collection_projection_receipt"));
    }
    Map<String,Object> rain(String seq,String issued,String command){var row=new LinkedHashMap<String,Object>();row.putAll(Map.of("stnId","108","tmFc",issued,"tmSeq",seq,"areaCode","Z_TEST","areaName","SYNTHETIC region","warnVar","2","warnStress","1","command",command,"startTime","20261101120000","cancel","0"));if(command.equals("2"))row.put("endTime",issued);return row;}
    void mapping(String code,String version){state.mappings.put("Z_TEST",new OfficialWarningAreaMappingPort.Mapping("Z_TEST",Set.of(code),URI.create("https://www.kma.go.kr/SYNTHETIC-mapping"),version,Instant.parse("2020-01-01T00:00:00Z"),state.until,true));}
    @Test void rainNewCorrectionConflictOlderAndLiftPreserveOfficialOrder(){
        assign(rain,"KMA_RAIN_CODES");mapping("SYNTHETIC_A","map1");var first=rain("1","20261101120100","1");assertEquals(State.COMPLETE,collect(rain,"KMA_RAIN_CODES",first).state());
        var conflict=rain("2","20261101120100","3");assertEquals(State.FAILED,collect(rain,"KMA_RAIN_CODES",conflict).state());assertEquals(1,count("source_disaster_revision"));
        var change=rain("3","20261101120200","3");assertEquals(State.COMPLETE,collect(rain,"KMA_RAIN_CODES",change).state());assertEquals(State.COMPLETE,collect(rain,"KMA_RAIN_CODES",first).state());assertEquals(2,count("source_disaster_revision"));
        var lift=rain("4","20261101120300","2");assertEquals(State.COMPLETE,collect(rain,"KMA_RAIN_CODES",lift).state());assertEquals("LIFTED",sql.queryForObject("select status from disaster",String.class));assertEquals(1,event("NEW"));
    }
    @ParameterizedTest @ValueSource(strings={"MISSING_MAPPING","PARTIAL_MAPPING","UNKNOWN_ADMIN","CANCEL","MISSING_PREDECESSOR"})
    void invalidRainNeverPublishesFactsOrProgress(String scenario){
        assign(rain,"KMA_RAIN_CODES");mapping("SYNTHETIC_A","map1");var row=rain("1","20261101120100","1");
        switch(scenario){case "MISSING_MAPPING"->state.mappings.clear();case "PARTIAL_MAPPING"->{var m=state.mappings.get("Z_TEST");state.mappings.put("Z_TEST",new OfficialWarningAreaMappingPort.Mapping(m.warningZone(),m.exactAdminAreaCodes(),m.evidenceUrl(),m.version(),m.validFrom(),m.validUntil(),false));}case "UNKNOWN_ADMIN"->mapping("NO_SUCH_ADMIN","map1");case "CANCEL"->row.put("cancel","1");case "MISSING_PREDECESSOR"->row.put("command","2");}
        assertEquals(State.FAILED,collect(rain,"KMA_RAIN_CODES",row).state());assertEquals(0,count("disaster"));assertEquals(0,event("NEW"));assertEquals(0,count("collection_domain_coverage"));assertEquals(0,count("collection_domain_cursor"));
    }
    @Test void changedMappingRevalidatesDuplicateWithoutNewIncidentRevision(){
        assign(rain,"KMA_RAIN_CODES");mapping("SYNTHETIC_A","map1");var row=rain("1","20261101120100","1");collect(rain,"KMA_RAIN_CODES",row);
        mapping("SYNTHETIC_B","map2");assertEquals(State.COMPLETE,collect(rain,"KMA_RAIN_CODES",row).state());assertEquals(1,count("source_disaster_revision"));assertEquals(1,event("NEW"));assertEquals(1,event("REVISION"));
        assertEquals(List.of("SYNTHETIC_B"),sql.queryForList("select area_code from disaster_area where basis='OFFICIAL' and area_role='IMPACT'",String.class));
    }
    @Test void messageClassificationCannotCreateFireFromSnOrText(){
        assign(message,"MOIS_MESSAGES");state.coverage=false;
        for(String hazard:List.of("산불","호우")){
            var row=Map.<String,Object>of("SN",hazard,"DST_SE_NM",hazard,"MSG_CN","SYNTHETIC 산불 발생 keyword only","CRT_DT","2026/11/01 12:01:00");
            assertEquals(State.FAILED,collect(message,"MOIS_MESSAGES",row).state());
        }
        assertEquals(0,count("disaster"));assertEquals(0,count("collection_domain_cursor"));assertEquals(2,count("collection_http_archive"));assertEquals(2,count("collection_projection_receipt"));
    }
    @Test void confirmedFireAliasesUseOneExplicitCanonicalDomainSource(){
        assign(message,"MOIS_MESSAGES");UUID t=sql.queryForObject("select target_id from collection_source_assignment where source_id=? and epoch=?",UUID.class,message,epoch);assign(alias,"MOIS_DISASTER_MESSAGES",t,"MOIS_MESSAGES");state.canonical=message;
        var row=Map.<String,Object>of("SN","SYNTHETIC-SN","DST_SE_NM","산불","MSG_CN","SYNTHETIC officially linked fixture","CRT_DT","2026-11-01 12:01:00");allow(message,OfficialProviderClient.Provider.WILDFIRE_MESSAGES,row,"synthetic-fire",1);
        assertEquals(State.COMPLETE,collect(message,"MOIS_MESSAGES",row).state());assertEquals(State.COMPLETE,collect(alias,"MOIS_MESSAGES",row).state());assertEquals(1,event("NEW"));assertEquals(1,event("REVISION"));assertEquals(2,count("collection_raw_record"));
    }
    Map<String,Object> shelter(String active){return Map.of("ARCD","SYNTHETIC","ACMDFCLTY_SN","1","VT_ACMDFCLTY_NM","SYNTHETIC shelter","LA","37.1","LO","127.1","USE_SE_CD",active,"VT_ACMD_PSBL_NMPR","12");}
    @Test void shelterUsesExistingIdentityAndExplicitDeactivationOnly(){
        assign(shelter,"MOIS_SHELTERS");assertEquals(State.COMPLETE,collect(shelter,"MOIS_SHELTERS",shelter("1")).state());UUID before=sql.queryForObject("select shelter_id from shelter",UUID.class);
        assertEquals(State.COMPLETE,collect(shelter,"MOIS_SHELTERS",shelter("1")).state());assertEquals(1,count("shelter"));assertEquals(1,count("collection_raw_revision"));
        assertEquals(State.COMPLETE,collect(shelter,"MOIS_SHELTERS",shelter("0")).state());assertEquals(before,sql.queryForObject("select shelter_id from shelter",UUID.class));assertFalse(sql.queryForObject("select active from shelter",Boolean.class));assertEquals(0,count("disaster"));
    }
    @Test void jpaAndJdbcWritesRollbackTogetherWithDefaultServiceProxies(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","valid");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);
        var flushed=new java.util.concurrent.atomic.AtomicBoolean();
        CollectionProjectionPort failing=c->{var result=projection.projectVerifiedArchive(c);var em=EntityManagerFactoryUtils.getTransactionalEntityManager(manager.getEntityManagerFactory());em.persist(new ProjectionTxProbe(UUID.randomUUID()));em.flush();assertEquals(1,count("projection_tx_probe"));flushed.set(true);throw new IllegalStateException("SYNTHETIC after JPA and JDBC writes");};
        assertEquals(State.FAILED,coordinator("KMA_PORTAL_EQK_MSG",List.of(row),failing).collect(quake,DAY,DAY,10).state());
        assertTrue(flushed.get(),"The ORM INSERT must actually execute before the rollback failure");
        for(String table:List.of("disaster","source_disaster_revision","disaster_runtime_outbox","collection_raw_record","projection_tx_probe","collection_domain_coverage","collection_domain_cursor"))assertEquals(0,count(table),table);
        assertEquals(1,count("collection_http_archive"));assertEquals(0,sql.queryForObject("select count(*) from collection_http_archive where classification='NORMAL'",Integer.class));
        assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",row).state());assertEquals(1,event("NEW"));
    }
    @Test void ownershipLossBeforeFinalCommitRollsBackProjection(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","valid");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);
        CollectionProjectionPort lost=c->{var result=projection.projectVerifiedArchive(c);sql.update("update collection_target_lease set lease_until=clock_timestamp()-interval '1 second' where run_id=?",c.run());sql.update("update collection_job_lock set lease_until=clock_timestamp()-interval '1 second' where run_id=?",c.run());return result;};
        assertEquals(State.FAILED,coordinator("KMA_PORTAL_EQK_MSG",List.of(row),lost).collect(quake,DAY,DAY,10).state());
        assertEquals(0,count("disaster"));assertEquals(0,event("NEW"));assertEquals(0,count("collection_domain_cursor"));
    }
    @Test void sameAnnouncementCorrectionAddsRawAndOfficialRevisionWithoutNewEvent(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","first");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",row).state());
        var correction=quake("1","20261101120100","corrected same announcement");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,correction,"q",2);
        assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",correction).state());assertEquals(1,count("collection_raw_record"));assertEquals(2,count("collection_raw_revision"));assertEquals(2,count("source_disaster_revision"));assertEquals(1,event("NEW"));assertEquals(2,event("REVISION"));
    }
    @Test void ormAndDomainJdbcCommitTogetherOnTheDefaultManager(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","committed");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);
        CollectionProjectionPort commit=c->{var result=projection.projectVerifiedArchive(c);var em=EntityManagerFactoryUtils.getTransactionalEntityManager(manager.getEntityManagerFactory());em.persist(new ProjectionTxProbe(UUID.randomUUID()));em.flush();return result;};
        assertEquals(State.COMPLETE,coordinator("KMA_PORTAL_EQK_MSG",List.of(row),commit).collect(quake,DAY,DAY,10).state());assertEquals(1,count("projection_tx_probe"));assertEquals(1,count("disaster"));assertEquals(1,event("NEW"));
    }
    @Test void cursorDoesNotJumpOverMissingDays(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var last=quake("3","20261103120100","third day");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,last,"third",1);
        assertEquals(State.COMPLETE,coordinator("KMA_PORTAL_EQK_MSG",List.of(last),projection).collect(quake,DAY.plusDays(2),DAY.plusDays(2),10).state());assertEquals(0,count("collection_domain_cursor"));
        var first=quake("1","20261101120100","first day");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,first,"first",1);collect(quake,"KMA_PORTAL_EQK_MSG",first);
        assertEquals(DAY,sql.queryForObject("select through_date from collection_domain_cursor",java.sql.Date.class).toLocalDate());
        var middle=quake("2","20261102120100","second day");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,middle,"middle",1);
        assertEquals(State.COMPLETE,coordinator("KMA_PORTAL_EQK_MSG",List.of(middle),projection).collect(quake,DAY.plusDays(1),DAY.plusDays(1),10).state());
        assertEquals(DAY.plusDays(2),sql.queryForObject("select through_date from collection_domain_cursor",java.sql.Date.class).toLocalDate());
    }
    @Test void independentCoverageDenialDoesNotAdvanceWatermark(){
        assign(quake,"KMA_PORTAL_EQK_MSG");state.coverage=false;var row=quake("1","20261101120100","manual only");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);
        assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",row).state());assertEquals(1,event("NEW"));assertEquals(0,count("collection_domain_coverage"));assertEquals(0,count("collection_domain_cursor"));
    }
    @Test void domainMigrationPreservesOldHttpOnlySuccessAndArchive(){
        String old="collection3_upgrade_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+old);
        try{
            var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+old,"eodiisseo_audit","");
            var locations=new String[]{"classpath:db/migration","classpath:db/owned-runtime","classpath:db/collection-executor"};
            Flyway.configure().dataSource(ds).locations(locations).load().migrate();var db=new JdbcTemplate(ds);var tm=new DataSourceTransactionManager(ds);
            UUID source=db.queryForObject("select source_id from data_source where endpoint_key='KMA_EARTHQUAKE'",UUID.class),target=UUID.randomUUID();
            db.update("insert into collection_contract values('KMA_PORTAL_EQK_MSG',true)");db.update("insert into collection_target values(?,'synthetic-legacy','KMA_PORTAL_EQK_MSG',true)",target);
            db.update("insert into collection_source_assignment values(?,1,'KMA_EARTHQUAKE',?,'KMA_PORTAL_EQK_MSG','q','COMMON_COLLECTION_V1','SYNTHETIC old approval',true,true)",source,target);db.update("update data_source set collection_assignment_epoch=1 where source_id=?",source);
            db.update("insert into collection_quota_window values('q',now()-interval '1 hour',now()+interval '1 hour',1,0,0,now(),'SYNTHETIC quota')");
            var owner=new CollectionOwnership(new JdbcCollectionOwnershipStore(db,tm),new CollectionContractRegistry(),Duration.ofSeconds(30));var journal=new JdbcCollectionExecutionStore(db,tm);var claim=owner.claim(source,"LIVE");
            journal.start(claim,new Request(DAY,DAY,1,10,Duration.ofSeconds(3)),new Evidence(true,true,true,true,"SYNTHETIC old HTTP contract"));var attempt=journal.reserve(claim,1,1).orElseThrow();
            var reply=new Reply(200,"SYNTHETIC old empty raw".getBytes(),Duration.ZERO);var policy=new CollectionArchivePolicy(Duration.ofHours(1),"SYNTHETIC old retention",Set.of());journal.received(attempt,reply,policy.inspect(reply.body()),policy);journal.validated(attempt,0,0);owner.finish(claim,CollectionOwnership.Outcome.SUCCESS,()->journal.terminal(claim,State.COMPLETE,"COMPLETE_RESPONSE"));
            var before=db.queryForObject("select to_jsonb(e)::text from collection_http_execution e",String.class);var run=db.queryForObject("select to_jsonb(r)::text from collection_run r",String.class);var archive=db.queryForObject("select to_jsonb(a)::text from collection_http_archive a",String.class);
            var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime","classpath:db/collection-executor","classpath:db/collection-projection").load();assertEquals(1,flyway.migrate().migrationsExecuted);flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
            assertEquals(before,db.queryForObject("select (to_jsonb(e)-array['projection_required','domain_phase'])::text from collection_http_execution e",String.class));assertEquals(run,db.queryForObject("select to_jsonb(r)::text from collection_run r",String.class));assertEquals(archive,db.queryForObject("select to_jsonb(a)::text from collection_http_archive a",String.class));
            assertNull(db.queryForObject("select domain_phase from collection_http_execution",String.class));assertEquals(0,db.queryForObject("select count(*) from collection_projection_receipt",Integer.class));
        }finally{admin.execute("drop database "+old);}
    }
    @Test void realLeaseExpiryRejectsLateProjectionWithoutLateFailureWrites(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","expiry");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);
        var original=ownership;var reached=new java.util.concurrent.atomic.AtomicBoolean();
        ownership=new CollectionOwnership(new JdbcCollectionOwnershipStore(sql,manager),new CollectionContractRegistry(),Duration.ofSeconds(5));
        try{
            CollectionProjectionPort late=c->{var result=projection.projectVerifiedArchive(c);assertEquals(1,count("disaster"));reached.set(true);try{Thread.sleep(5500);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return result;};
            assertEquals(State.HELD,coordinator("KMA_PORTAL_EQK_MSG",List.of(row),late).collect(quake,DAY,DAY,10).state());
            assertTrue(reached.get());assertEquals(0,count("disaster"));assertEquals(0,event("NEW"));assertEquals(0,count("collection_domain_cursor"));assertEquals(0,count("collection_projection_receipt"));
            assertEquals("RUNNING",sql.queryForObject("select status from collection_run",String.class));assertEquals(1,count("collection_http_archive"));
        }finally{ownership=original;}
    }
    @Test void domainAdmissionRevokedDuringNormalizationRollsBackAllFacts(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","revoke");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);state.duringLink=()->state.admitted=false;
        assertEquals(State.FAILED,collect(quake,"KMA_PORTAL_EQK_MSG",row).state());assertEquals(0,count("disaster"));assertEquals(0,count("collection_raw_record"));assertEquals(0,event("NEW"));assertEquals(0,count("collection_domain_coverage"));
    }
    @Test void emptyShelterResponseNeverDeactivatesMissingFacilities(){
        assign(shelter,"MOIS_SHELTERS");collect(shelter,"MOIS_SHELTERS",shelter("1"));
        assertEquals(State.FAILED,coordinator("MOIS_SHELTERS",List.of(),projection).collect(shelter,DAY,DAY,10).state());assertTrue(sql.queryForObject("select active from shelter",Boolean.class));
        state.empty=true;assertEquals(State.COMPLETE,coordinator("MOIS_SHELTERS",List.of(),projection).collect(shelter,DAY,DAY,10).state());assertTrue(sql.queryForObject("select active from shelter",Boolean.class));
    }
    @Test void invalidShelterCoordinatesRollBackRawAndFacility(){
        assign(shelter,"MOIS_SHELTERS");var row=new LinkedHashMap<>(shelter("1"));row.put("LA","999");
        assertEquals(State.FAILED,collect(shelter,"MOIS_SHELTERS",row).state());assertEquals(0,count("shelter"));assertEquals(0,count("collection_raw_record"));assertEquals(0,count("collection_domain_cursor"));assertEquals(1,count("collection_http_archive"));
    }
    @Test void callerTransactionCannotRemainOpenAcrossHttp(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","ambient tx");allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);
        var outer=new TransactionTemplate(manager);outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var result=outer.execute(status->collect(quake,"KMA_PORTAL_EQK_MSG",row));
        assertEquals(State.HELD,result.state());assertEquals("HTTP_REQUIRES_NO_AMBIENT_TRANSACTION",result.reason());
        assertEquals(0,state.httpCalls.get());assertEquals(0,count("collection_run"));assertEquals(0,count("collection_http_attempt"));
    }
    @Test void commonRawAndOfficialHashesAreNotAssumedEqual(){
        assign(quake,"KMA_PORTAL_EQK_MSG");var row=quake("1","20261101120100","nested");var nested=new LinkedHashMap<String,Object>();nested.put("z","last");nested.put("a","first");row.put("SYNTHETIC_METADATA",nested);allow(quake,OfficialProviderClient.Provider.EARTHQUAKE,row,"q",1);
        assertEquals(State.COMPLETE,collect(quake,"KMA_PORTAL_EQK_MSG",row).state());
        assertNotEquals(sql.queryForObject("select raw_hash from collection_projection_receipt",String.class),sql.queryForObject("select official_hash from collection_projection_receipt",String.class));
    }
}
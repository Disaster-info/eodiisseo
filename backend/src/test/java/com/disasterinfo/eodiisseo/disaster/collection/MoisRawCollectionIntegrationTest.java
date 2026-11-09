package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.disaster.service.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import static org.assertj.core.api.Assertions.*;
import static com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES;

/** Synthetic wire data only: no timestamp conversion, incident identity, or provider snapshot claim. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Timeout(90)
class MoisRawCollectionIntegrationTest {
    JdbcTemplate admin,sql; OfficialCollectionService service; OwnedDatabase db;
    String name; UUID source=UUID.randomUUID();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-02T04:00:00Z"),ZoneOffset.UTC);
    final LocalDate day=LocalDate.of(2026,11,1);
    @BeforeAll void open(){
        String base=System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")) || !"jdbc:postgresql://127.0.0.1:55433/postgres".equals(base))
            throw new IllegalStateException("Disposable audit cluster only");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,"eodiisseo_audit",""));
        name="owned_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+name);
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+name,"eodiisseo_audit","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime").load().migrate();
        sql=new JdbcTemplate(ds);
        // Exclusive fixture only; no production source activation changes.
        sql.update("update data_source set active=false where endpoint_key='SAFETYDATA_DISASTER_MESSAGE'");
        sql.update("insert into data_source values(?,'SYNTHETIC MOIS','DISASTER','MOIS_DISASTER_MESSAGES',5,true)",source);
        db=new OwnedDatabase(sql);var events=new DisasterRuntimeEvents(db);var earthquakes=new DisasterService(db);
        var incidents=new VerifiedIncidentService(db,earthquakes,events,Optional.empty());
        service=new OfficialCollectionService(db,new OfficialRecordNormalizer(db,earthquakes,events,incidents),new DataSourceTransactionManager(ds),clock);
    }
    @AfterAll void close(){if(admin!=null&&name!=null&&name.matches("owned_[a-f0-9]{32}"))admin.execute("DROP DATABASE "+name+" WITH (FORCE)");}
    @BeforeEach void reset(){
        for(String table:List.of("collection_job_lock","collection_run","owned_collection_item","owned_collection_cursor"))sql.update("delete from "+table);
    }
    Map<String,Object> row(int index){
        var row=new LinkedHashMap<String,Object>();row.put("SN",String.format(java.util.Locale.ROOT,"%022d",index+1));
        row.put("CRT_DT","2026/11/01 10:00:00");row.put("REG_YMD","2026/11/01 10:00:01.123456789");
        row.put("MDFCN_YMD","2026/11/01 10:00:02.123456789");row.put("MSG_CN","SYNTHETIC ONLY");
        row.put("DST_SE_ID","SYNTHETIC");row.put("DST_SE_NM","산불");row.put("EMRG_STEP_ID","SYNTHETIC");row.put("EMRG_STEP_NM","안전안내");
        row.put("RCPTN_RGN_ID","SYNTHETIC");row.put("RCPTN_RGN_NM","SYNTHETIC RECIPIENT");return row;
    }
    List<Map<String,Object>> hundred(){var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<100;i++)rows.add(row(i));return rows;}
    OfficialHttp.Reply reply(int page,int total,List<Map<String,Object>> rows){
        return new OfficialHttp.Reply(200,tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(
            Map.of("header",Map.of("resultCode","00"),"pageNo",page,"numOfRows",100,"totalCount",total,"body",rows)));
    }
    OfficialProviderClient client(IntFunction<OfficialHttp.Reply> replies,AtomicInteger calls){
        return new OfficialProviderClient(WILDFIRE_MESSAGES,(uri,timeout)->{
            calls.incrementAndGet();
            int page=Integer.parseInt(Arrays.stream(uri.getRawQuery().split("&")).filter(part->part.startsWith("pageNo=")).findFirst().orElseThrow().substring(7));
            return replies.apply(page);
        });
    }
    void ready(){sql.update("update owned_collection_cursor set next_attempt_at='-infinity' where source_id=?",source);}
    OfficialCollectionService.Outcome collect(OfficialProviderClient client,LocalDate from){
        ready();return service.collect(source,client,"synthetic-key",from,from,"owned-disaster-development");
    }
    OfficialCollectionService.Outcome collect(Map<String,Object> row){return collect(client(p->reply(p,1,List.of(row)),new AtomicInteger()),day);}
    long count(String table){return sql.queryForObject("select count(*) from "+table,Long.class);}
    void noIncidentOrCoverage(){
        for(String table:List.of("disaster","source_disaster","source_disaster_revision","owned_incident_head","disaster_runtime_outbox","ai_summary","summary_runtime_task","owned_collection_coverage"))
            assertThat(count(table)).as(table).isZero();
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source)).isNull();
        assertThat(sql.queryForObject("select last_success_at from owned_collection_cursor where source_id=?",java.sql.Timestamp.class,source)).isNull();
        assertThat(count("collection_job_lock")).isZero();
    }
    void archived(OfficialCollectionService.Outcome result,int received){
        assertThat(result.status()).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");assertThat(result.received()).isEqualTo(received);assertThat(result.normalized()).isZero();
        assertThat(sql.queryForObject("select count(*) from collection_run where status='SUCCESS'",Long.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from collection_run where status='FAILED'",Long.class)).isEqualTo(count("collection_run"));
        assertThat(sql.queryForObject("select count(*) from collection_run where error_code='INCREMENTAL_CONTRACT_REQUIRED' and finished_at is not null",Long.class)).isPositive();
        assertThat(sql.queryForObject("select last_failure_code from owned_collection_cursor where source_id=?",String.class,source)).isEqualTo("INCREMENTAL_CONTRACT_REQUIRED");
        noIncidentOrCoverage();
    }
    @Test void singleAndMultiplePagesArchiveWireAndVersionsWithoutClaimingIncrementalCompletion(){
        archived(collect(row(0)),1);assertThat(count("owned_collection_item")).isEqualTo(1);
        byte[] wire=sql.queryForObject("select raw_body from owned_collection_page",byte[].class);
        assertThat(wire).isEqualTo(reply(1,1,List.of(row(0))).body());
        archived(collect(row(0)),1);assertThat(count("owned_collection_item")).isEqualTo(1);
        reset();var calls=new AtomicInteger();
        archived(collect(client(p->reply(p,101,p==1?hundred():List.of(row(100))),calls),day),101);
        assertThat(calls.get()).isEqualTo(2);assertThat(count("owned_collection_item")).isEqualTo(101);assertThat(count("owned_collection_page")).isEqualTo(2);
    }
    @Test void sameSnChangedContentAndOlderModificationRemainSeparateWithoutChoosingLatest(){
        var row=row(0);archived(collect(row),1);String original=OfficialProviderClient.canonical(row);
        row.put("MSG_CN","SYNTHETIC CORRECTION");row.put("MDFCN_YMD","2026/10/31 23:59:59.999999999");
        archived(collect(row),1);archived(collect(row),1);
        assertThat(count("owned_collection_item")).isEqualTo(2);
        assertThat(sql.queryForList("select canonical_json from owned_collection_item",String.class)).containsExactlyInAnyOrder(original,OfficialProviderClient.canonical(row));
        assertThat(sql.queryForObject("select count(distinct provider_record_key) from owned_collection_item",Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"2026/11/01 23:59:59","2026-11-01 23:59:59","2026/11/01 23:59:59.123456789","2026/02/30 12:00:00","2026/11/01 24:00:00","2026/11/01 10:60:00","2026/11/01 10:00:60","UNKNOWN",""})
    void datesRemainExactRawStringsWithNoInventedTimezoneOrNormalizedTime(String value){
        var row=row(0);row.put("CRT_DT",value);row.put("REG_YMD",value);row.put("MDFCN_YMD",value);
        archived(collect(row),1);
        var canonical=sql.queryForObject("select canonical_json from owned_collection_item",String.class);
        var restored=tools.jackson.databind.json.JsonMapper.builder().build().readValue(canonical,Map.class);
        assertThat(restored).containsEntry("CRT_DT",value).containsEntry("REG_YMD",value).containsEntry("MDFCN_YMD",value);
        assertThat(sql.queryForObject("select classification from owned_collection_item",String.class)).isEqualTo("WILDFIRE_MESSAGE_UNLINKED");
    }
    @Test void missingIdentityAndUnknownDisasterRemainRawUnclassified(){
        var row=row(0);row.remove("SN");row.remove("CRT_DT");archived(collect(row),1);
        assertThat(sql.queryForObject("select classification from owned_collection_item",String.class)).isEqualTo("INVALID_FACTS");
        row=row(1);row.put("DST_SE_NM","SYNTHETIC UNKNOWN");archived(collect(row),1);
        assertThat(sql.queryForObject("select count(*) from owned_collection_item where classification='UNCLASSIFIED_MESSAGE'",Long.class)).isEqualTo(1);
    }
    @Test void numericSnAndLeadingZeroStringAreNotLossilyCoercedOrInferredToBeIncidentIds(){
        var row=row(0);row.put("SN",new java.math.BigInteger("9999999999999999999999"));archived(collect(row),1);
        assertThat(sql.queryForObject("select provider_record_key from owned_collection_item",String.class)).isEqualTo("9999999999999999999999");
        row=row(0);archived(collect(row),1);assertThat(count("owned_collection_item")).isEqualTo(2);
    }
    @Test void overlappingQueriesPreserveSameSnAndAllRawPagesWithoutDiscardingByUnknownDateBoundary(){
        var row=row(0);row.put("CRT_DT","2026/10/31 23:59:59");archived(collect(row),1);
        archived(collect(client(p->reply(p,1,List.of(row)),new AtomicInteger()),day.plusDays(1)),1);
        assertThat(count("owned_collection_item")).isEqualTo(1);assertThat(count("owned_collection_page")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"WITHIN_PAGE","BETWEEN_PAGES","CHANGED_SN","TOTAL_CHANGE","MISSING_PAGE","PAGE_MOVED","TIMEOUT","ZERO_WITH_ROWS"})
    void unstablePagesRollbackAllNewRawData(String mode){
        var calls=new AtomicInteger();var client=client(p->{
            if(mode.equals("WITHIN_PAGE"))return reply(p,2,List.of(row(0),row(0)));
            if(mode.equals("ZERO_WITH_ROWS"))return reply(p,0,List.of(row(0)));
            if(p==1)return reply(p,101,hundred());
            if(mode.equals("TIMEOUT"))throw new CollectionFailure("TIMEOUT");
            if(mode.equals("MISSING_PAGE"))return reply(p,101,List.of());
            if(mode.equals("PAGE_MOVED"))return reply(3,101,List.of(row(100)));
            if(mode.equals("TOTAL_CHANGE"))return reply(p,102,List.of(row(100)));
            var row=row(0);if(mode.equals("CHANGED_SN")){row.put("MSG_CN","SYNTHETIC CHANGED");row.put("MDFCN_YMD","UNKNOWN");}
            return reply(p,101,List.of(row));
        },calls);
        String expected=switch(mode){case "TIMEOUT"->"TIMEOUT";case "MISSING_PAGE"->"PAGE_INCOMPLETE";case "PAGE_MOVED"->"PAGE_MISMATCH";case "ZERO_WITH_ROWS"->"PAGE_TOTAL_INCONSISTENT";default->"PAGING_CHANGED";};
        assertThat(collect(client,day).status()).isEqualTo(expected);
        assertThat(count("owned_collection_page")).isZero();assertThat(count("owned_collection_item")).isZero();noIncidentOrCoverage();
        assertThat(sql.queryForObject("select error_code from collection_run",String.class)).isEqualTo(expected);
    }
    @Test void interruptedRunThenNewWorkerRetriesWithoutPartialRowsOrExtraVersions(){
        var calls=new AtomicInteger();var broken=client(p->{if(p==2)throw new CollectionFailure("INTERRUPTED");return reply(p,101,hundred());},calls);
        assertThat(collect(broken,day).status()).isEqualTo("INTERRUPTED");assertThat(count("owned_collection_item")).isZero();
        // Reconstruct the worker/service over persisted state, as after a process restart.
        var restartedDb=new OwnedDatabase(sql);var events=new DisasterRuntimeEvents(restartedDb);var earthquakes=new DisasterService(restartedDb);
        var incidents=new VerifiedIncidentService(restartedDb,earthquakes,events,Optional.empty());
        service=new OfficialCollectionService(restartedDb,new OfficialRecordNormalizer(restartedDb,earthquakes,events,incidents),
            new DataSourceTransactionManager(sql.getDataSource()),clock);
        var repaired=client(p->reply(p,101,p==1?hundred():List.of(row(100))),new AtomicInteger());
        archived(collect(repaired,day),101);assertThat(count("owned_collection_item")).isEqualTo(101);
        archived(collect(repaired,day),101);assertThat(count("owned_collection_item")).isEqualTo(101);
    }
    @Test void databaseFailureAfterPageInsertRollsBackWholeArchiveAndCanRetry(){
        // Fault injection is confined to this generated disposable DB; no migration/schema contract is changed.
        sql.execute("alter table owned_collection_item add constraint synthetic_reject check(provider_record_key <> 'SYNTHETIC_DB_FAIL')");
        var row=row(0);row.put("SN","SYNTHETIC_DB_FAIL");
        try{
            assertThat(collect(row).status()).isEqualTo("PERSISTENCE_OR_NORMALIZATION");
            assertThat(count("owned_collection_page")).isZero();assertThat(count("owned_collection_item")).isZero();noIncidentOrCoverage();
        }finally{sql.execute("alter table owned_collection_item drop constraint synthetic_reject");}
        archived(collect(row),1);assertThat(count("owned_collection_item")).isEqualTo(1);
    }
    @Test void existingCursorAndSuccessTimestampArePreservedAndBackoffDoesNotCallAgain(){
        var past=java.sql.Date.valueOf("2026-10-30");var last=java.sql.Timestamp.from(Instant.parse("2026-10-31T00:00:00Z"));
        sql.update("insert into owned_collection_cursor(source_id,completed_through,coverage_start,last_success_at) values(?,?,?,?)",source,past,past,last);
        assertThat(collect(row(0)).status()).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor",java.sql.Date.class)).isEqualTo(past);
        assertThat(sql.queryForObject("select coverage_start from owned_collection_cursor",java.sql.Date.class)).isEqualTo(past);
        assertThat(sql.queryForObject("select last_success_at from owned_collection_cursor",java.sql.Timestamp.class)).isEqualTo(last);
        var calls=new AtomicInteger();
        assertThat(service.collect(source,client(p->reply(p,0,List.of()),calls),"synthetic-key",day,day,"owned-disaster-development").status()).isEqualTo("BUSY_OR_RATE_LIMITED");
        assertThat(calls.get()).isZero();assertThat(count("collection_run")).isEqualTo(1);
    }
    @Test void enabledSchedulerStillBlocksMessagesBeforeSourceOrKeyLookup(){
        var env=new MockEnvironment().withProperty("eodiisseo.disaster.collection.owner","owned-disaster-development")
            .withProperty("eodiisseo.owned-runtime.enabled","true").withProperty("eodiisseo.disaster.collection.wildfire-messages.enabled","true");
        try(var scheduler=new OfficialCollectionScheduler(service,db,env,clock)){
            scheduler.poll();assertThat(scheduler.statusSnapshot()).containsEntry(WILDFIRE_MESSAGES,"INCREMENTAL_CONTRACT_REQUIRED");
            assertThat(count("collection_run")).isZero();assertThat(count("owned_collection_cursor")).isZero();
        }
    }
    @Test void failurePreservesPreviouslyArchivedRawVersionsAndPages(){
        var row=row(0);archived(collect(row),1);
        var items=sql.queryForList("select * from owned_collection_item");
        long pages=count("owned_collection_page");
        assertThat(collect(client(p->reply(p,2,List.of(row(1),row(1))),new AtomicInteger()),day).status()).isEqualTo("PAGING_CHANGED");
        assertThat(sql.queryForList("select * from owned_collection_item")).isEqualTo(items);
        assertThat(count("owned_collection_page")).isEqualTo(pages);noIncidentOrCoverage();
    }
    @Test void observedSlashDateDoesNotGrantAnAssumedKstIncidentEvenWithProposedLink(){
        var row=row(0);archived(collect(row),1);
        var earthquakes=new DisasterService(db);var events=new DisasterRuntimeEvents(db);
        com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort proposed=(s,p,a,h)->Optional.of(
            new com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.Link(s,p,a,h,"SYNTHETIC_INCIDENT",1,
                Instant.parse("2026-11-01T01:00:00Z"),Instant.parse("2026-11-01T01:00:00Z"),
                com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,null,
                java.net.URI.create("https://www.safetydata.go.kr/synthetic-contract"),"SYNTHETIC ONLY"));
        var verifier=new VerifiedIncidentService(db,earthquakes,events,Optional.of(proposed));
        assertThat(verifier.normalize(source,WILDFIRE_MESSAGES,row,OfficialProviderClient.canonical(row),clock.instant()).classification())
            .isEqualTo("INVALID_INCIDENT_EVIDENCE");
        noIncidentOrCoverage();
    }    @Test void unsupportedEndDateDoesNotClaimLeaseSpendBudgetOrSend(){
        var calls=new AtomicInteger();var client=client(p->reply(p,0,List.of()),calls);
        assertThatThrownBy(()->service.collect(source,client,"synthetic-key",day,day.plusDays(1),"owned-disaster-development"))
            .isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("UNSUPPORTED_END_DATE"));
        assertThat(calls.get()).isZero();assertThat(count("collection_run")).isZero();
        assertThat(count("owned_collection_cursor")).isZero();assertThat(count("collection_job_lock")).isZero();
    }}
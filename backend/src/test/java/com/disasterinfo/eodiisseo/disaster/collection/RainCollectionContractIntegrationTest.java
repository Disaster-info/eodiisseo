package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.disaster.service.*;
import com.disasterinfo.eodiisseo.disaster.adapter.VerifiedWarningImpactReader;
import com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import static org.assertj.core.api.Assertions.*;

/** Stable datasets here are synthetic proofs only, never a claim of provider snapshot guarantees. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class RainCollectionContractIntegrationTest {
    JdbcTemplate admin,sql;
    OfficialCollectionService service;
    String name;
    UUID source=UUID.randomUUID();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-02T04:00:00Z"),ZoneOffset.UTC);
    final LocalDate day=LocalDate.of(2026,11,1);
    @BeforeAll void open(){
        String base=System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")) ||
            !"jdbc:postgresql://127.0.0.1:55433/postgres".equals(base))throw new IllegalStateException("Disposable audit cluster only");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,"eodiisseo_audit",""));
        name="owned_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+name);
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+name,"eodiisseo_audit","");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime").load().migrate();
        sql=new JdbcTemplate(ds);
        // Explicitly exclusive synthetic provider setup; no production activation/deactivation code.
        sql.update("update data_source set active=false where endpoint_key='KMA_WARNING'");
        sql.update("insert into data_source values(?,'SYNTHETIC RAIN','DISASTER','KMA_HEAVY_RAIN',5,true)",source);
        var db=new OwnedDatabase(sql);var events=new DisasterRuntimeEvents(db);var earthquakes=new DisasterService(db);
        var incidents=new VerifiedIncidentService(db,earthquakes,events,Optional.empty());
        var normalizer=new OfficialRecordNormalizer(db,earthquakes,events,incidents);
        service=new OfficialCollectionService(db,normalizer,new DataSourceTransactionManager(ds),clock);
    }
    @AfterAll void close(){
        if(admin!=null && name!=null && name.matches("owned_[a-f0-9]{32}"))admin.execute("DROP DATABASE "+name+" WITH (FORCE)");
    }
    @BeforeEach void reset(){
        for(String table:List.of("collection_job_lock","collection_run","owned_collection_item","disaster_runtime_outbox",
            "disaster_change","source_disaster_revision","source_disaster","disaster","owned_collection_cursor"))
            sql.update("delete from "+table);
    }
    Map<String,Object> row(int index){
        var r=new LinkedHashMap<String,Object>();
        r.put("stnId","108");r.put("tmFc","202611011000");r.put("tmSeq","1");
        r.put("areaCode","L_SYNTH_"+index);r.put("areaName","SYNTHETIC");
        r.put("warnVar","2");r.put("warnStress","0");r.put("command","1");
        r.put("startTime","202611011000");r.put("cancel","0");return r;
    }
    List<Map<String,Object>> hundred(){
        var list=new ArrayList<Map<String,Object>>();for(int i=0;i<100;i++)list.add(row(i));return list;
    }
    OfficialHttp.Reply reply(int page,int total,List<Map<String,Object>> rows){
        return new OfficialHttp.Reply(200,tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(
            Map.of("response",Map.of("header",Map.of("resultCode","00","resultMsg","NORMAL_SERVICE"),
                "body",Map.of("pageNo",page,"numOfRows",100,"totalCount",total,"items",Map.of("item",rows))))));
    }
    OfficialProviderClient client(IntFunction<OfficialHttp.Reply> pages,AtomicInteger calls){
        return new OfficialProviderClient(OfficialProviderClient.Provider.HEAVY_RAIN,(uri,timeout)->pages.apply(calls.incrementAndGet()));
    }
    OfficialCollectionService.Outcome collect(OfficialProviderClient client){
        sql.update("update owned_collection_cursor set next_attempt_at='-infinity' where source_id=?",source);
        return service.collect(source,client,"synthetic-key",day,day,"owned-disaster-development");
    }
    OfficialCollectionService.Outcome collect(Map<String,Object> row){
        return collect(client(p->reply(p,1,List.of(row)),new AtomicInteger()));
    }
    List<Object> watermark(){
        var rows=sql.queryForList("select completed_through,coverage_start,last_success_at from owned_collection_cursor where source_id=?",source);
        return rows.isEmpty()?Arrays.asList(null,null,null):Arrays.asList(rows.getFirst().get("completed_through"),
            rows.getFirst().get("coverage_start"),rows.getFirst().get("last_success_at"));
    }
    Map<String,Object> snapshot(){
        return Map.of("watermark",watermark(),
            "coverage",sql.queryForList("select * from owned_collection_coverage order by run_id"),
            "source",sql.queryForList("select * from source_disaster order by source_disaster_id"),
            "disaster",sql.queryForList("select * from disaster order by disaster_id"),
            "revision",sql.queryForList("select * from source_disaster_revision order by revision_id"),
            "events",sql.queryForList("select * from disaster_runtime_outbox order by event_id"),
            "pages",sql.queryForObject("select count(*) from owned_collection_page",Long.class),
            "items",sql.queryForObject("select count(*) from owned_collection_item",Long.class));
    }
    void failure(String code,Map<String,Object> before,OfficialCollectionService.Outcome result){
        assertThat(result.status()).isEqualTo(code);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(sql.queryForObject("select count(*) from collection_run where status='FAILED' and error_code=?",Long.class,code)).isEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from collection_job_lock",Long.class)).isZero();
    }
    @Test void stableSyntheticSingleAndMultiplePagesPersistOnlyTheirExactFiniteDataset(){
        assertThat(collect(row(0)).status()).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision",Long.class)).isEqualTo(1);
        assertThat(collect(row(0)).status()).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision",Long.class)).isEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class)).isEqualTo(2);
        reset();
        AtomicInteger calls=new AtomicInteger();
        assertThat(collect(client(p->reply(p,101,p==1?hundred():List.of(row(100))),calls)).status()).isEqualTo("SUCCESS");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from source_disaster",Long.class)).isEqualTo(101);
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision",Long.class)).isEqualTo(101);
        assertThat(sql.queryForObject("select count(*) from owned_collection_page",Long.class)).isEqualTo(2);
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source).toLocalDate()).isEqualTo(day);
    }
    @ParameterizedTest @ValueSource(strings={"ZERO_WITH_ROWS","EMPTY_ZERO","SHORT_MIDDLE","MISSING_MIDDLE","WRONG_PAGE","TOTAL_CHANGE","DUPLICATE","CONTENT_CHANGE","TIMEOUT"})
    void uncertainPaginationNeverWritesSuccessCoverageOrPartialState(String mode){
        var before=snapshot();var calls=new AtomicInteger();
        var client=client(p->{
            if(mode.equals("ZERO_WITH_ROWS"))return reply(p,0,List.of(row(0),row(1),row(2),row(3),row(4)));
            if(mode.equals("EMPTY_ZERO"))return reply(p,0,List.of());
            if(mode.equals("SHORT_MIDDLE"))return reply(p,101,List.of(row(0)));
            if(p==1)return reply(p,mode.equals("MISSING_MIDDLE")?201:101,hundred());
            if(mode.equals("TIMEOUT"))throw new CollectionFailure("TIMEOUT");
            if(mode.equals("MISSING_MIDDLE"))return reply(p,201,List.of());
            if(mode.equals("WRONG_PAGE"))return reply(3,101,List.of(row(100)));
            if(mode.equals("TOTAL_CHANGE"))return reply(p,102,List.of(row(100)));
            var last=row(0);if(mode.equals("CONTENT_CHANGE"))last.put("warnStress","1");
            return reply(p,101,List.of(last));
        },calls);
        String code=switch(mode){
            case "ZERO_WITH_ROWS" -> "PAGE_TOTAL_INCONSISTENT";
            case "EMPTY_ZERO" -> "EMPTY_RESULT_CONTRACT_REQUIRED";
            case "SHORT_MIDDLE","MISSING_MIDDLE" -> "PAGE_INCOMPLETE";
            case "WRONG_PAGE" -> "PAGE_MISMATCH";
            case "TIMEOUT" -> "TIMEOUT";
            default -> "PAGING_CHANGED";
        };
        failure(code,before,collect(client));
        assertThat(calls.get()).isEqualTo(Set.of("ZERO_WITH_ROWS","EMPTY_ZERO","SHORT_MIDDLE").contains(mode)?1:2);
    }
    @Test void failureAfterSuccessfulCoveragePreservesThePreviousWatermark(){
        assertThat(collect(row(0)).status()).isEqualTo("SUCCESS");var before=snapshot();
        failure("PAGE_TOTAL_INCONSISTENT",before,collect(client(p->reply(p,0,List.of(row(1))),new AtomicInteger())));
    }
    @Test void watchWarningMajorExtensionAndLaterCorrectionKeepSeverityAndImpactUnknown(){
        var row=row(0);assertThat(collect(row).status()).isEqualTo("SUCCESS");
        for(String[] change:List.of(new String[]{"7","1","1100"},new String[]{"7","2","1200"},
            new String[]{"3","2","1300"},new String[]{"6","2","1400"})){
            row.put("command",change[0]);row.put("warnStress",change[1]);row.put("tmFc","20261101"+change[2]);
            assertThat(collect(row).status()).isEqualTo("SUCCESS");
        }
        assertThat(sql.queryForObject("select source_status from source_disaster",String.class)).isEqualTo("MAJOR_WARNING:6");
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision",Long.class)).isEqualTo(5);
        assertThat(sql.queryForObject("select official_severity_id from disaster",UUID.class)).isNull();
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where event_kind='NEW'",Long.class)).isEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where event_kind='REVISION'",Long.class)).isEqualTo(5);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where event_kind='SEVERITY_INCREASE'",Long.class)).isZero();
        UUID id=sql.queryForObject("select disaster_id from disaster",UUID.class);
        assertThat(new VerifiedWarningImpactReader(new OwnedDatabase(sql),Optional.empty()).find(id,clock.instant()).knowledge()).isEqualTo(DisasterImpactAreaReader.Knowledge.UNKNOWN);
        assertThat(sql.queryForObject("select count(*) from disaster_area",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"2","8"})
    void documentedLiftUsesEndTimeAndDoesNotInferFromAggregateEnd(String command){
        var r=row(0);assertThat(collect(r).status()).isEqualTo("SUCCESS");
        r.put("tmFc","202611011500");r.put("command",command);r.put("startTime","0");r.put("endTime","202611011500");r.put("allEndTime","0");
        assertThat(collect(r).status()).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select status from disaster",String.class)).isEqualTo("LIFTED");
        assertThat(sql.queryForObject("select ended_at from disaster",java.sql.Timestamp.class).toInstant()).isEqualTo(Instant.parse("2026-11-01T06:00:00Z"));
    }
    @ParameterizedTest @ValueSource(strings={"MISSING_PREDECESSOR","SAME_TIME_CORRECTION","CANCEL","OLD","WRONG_START","MISSING_END","UNKNOWN_CODE"})
    void uncertainWarningStateNeverMutatesAPredecessorOrAdvancesCoverage(String mode){
        var r=row(0);
        if(!mode.equals("MISSING_PREDECESSOR"))assertThat(collect(r).status()).isEqualTo("SUCCESS");
        var before=snapshot();
        r.put("command","6");r.put("tmFc","202611011100");
        String code=switch(mode){
            case "MISSING_PREDECESSOR" -> "WARNING_PREDECESSOR_MISSING";
            case "SAME_TIME_CORRECTION" -> {r.put("tmFc","202611011000");r.put("tmSeq","2");r.put("warnStress","1");yield "WARNING_REVISION_ORDER_REQUIRED";}
            case "CANCEL" -> {r.put("cancel","1");yield "CANCELLED_OR_UNKNOWN_REPORT";}
            case "OLD" -> {r.put("tmFc","202611010959");yield "OUT_OF_ORDER_WARNING";}
            case "WRONG_START" -> {r.put("startTime","202611010930");yield "WARNING_PREDECESSOR_MISMATCH";}
            case "MISSING_END" -> {r.put("command","2");r.put("startTime","0");r.put("endTime","0");r.put("allEndTime","202611011100");yield "INVALID_FACTS";}
            default -> {r.put("command","9");yield "UNKNOWN_WARNING_CODE";}
        };
        failure(code,before,collect(r));
    }
    @Test void databaseWriteFailureRollsBackPagesDisasterRevisionAndOutbox(){
        var before=snapshot();var r=row(0);r.put("areaName","SYNTHETIC\u0000ADDRESS"); // PostgreSQL text rejects NUL after the disaster insert.
        failure("PERSISTENCE_OR_NORMALIZATION",before,collect(r));
    }
}


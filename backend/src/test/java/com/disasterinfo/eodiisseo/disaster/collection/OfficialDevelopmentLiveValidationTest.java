package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.disaster.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in development observation. All provider I/O is bounded and shared across A/B/C/D checks.
 * No actual item value, ID, URI, key, body, location, magnitude or exception cause is printed.
 * Historical dates require official publication evidence, not date scanning.
 */
@EnabledIfEnvironmentVariable(named="EODIISSEO_DISASTER_LIVE_VALIDATION",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(120)
class OfficialDevelopmentLiveValidationTest {
    private JdbcTemplate admin,sql; private String database; private TransactionTemplate tx;
    private final Map<OfficialProviderClient.Provider,List<Sample>> observed=new EnumMap<>(OfficialProviderClient.Provider.class);
    private final Map<OfficialProviderClient.Provider,UUID> sources=new EnumMap<>(OfficialProviderClient.Provider.class);
    private record Sample(String route,LocalDate day,int requestedPage,int status,String result,byte[] raw,OfficialProviderClient.Page page,UUID run) {
        @Override public String toString(){return "DevelopmentSample[redacted]";}
    }
    @BeforeAll void open(){
        assertEquals("development",System.getenv("EODIISSEO_DISASTER_KEY_ENVIRONMENT"),"Explicit development attestation required");
        assertEquals("jdbc:postgresql://127.0.0.1:55433/postgres",System.getenv("EODIISSEO_AUDIT_JDBC_URL"),"Disposable loopback audit cluster required");
        assertEquals("true",System.getenv("EODIISSEO_AUDIT_DISPOSABLE"),"Disposable database attestation required");
        database="owned_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(System.getenv("EODIISSEO_AUDIT_JDBC_URL"),"eodiisseo_audit",""));
        admin.execute("create database "+database);
        String url="jdbc:postgresql://127.0.0.1:55433/"+database;
        try{
            org.flywaydb.core.Flyway.configure().dataSource(url,"eodiisseo_audit","")
                .locations("classpath:db/migration","classpath:db/owned-runtime").load().migrate();
            var dataSource=new DriverManagerDataSource(url,"eodiisseo_audit","");
            sql=new JdbcTemplate(dataSource);tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }catch(RuntimeException failure){throw new AssertionError("Disposable validation setup failed; details withheld");}
    }
    @AfterAll void close(){
        if(admin!=null&&database!=null&&database.matches("owned_[a-f0-9]{32}"))admin.execute("drop database "+database+" with (force)");
        observed.clear();
    }
    /** Disposable validation setup only; never reactivate or relabel an existing source. */
    static UUID developmentSource(JdbcTemplate sql,OfficialProviderClient.Provider provider){
        var existing=sql.queryForList("select source_id,source_kind,active from data_source where endpoint_key=?",provider.endpointKey);
        if(!existing.isEmpty()){
            assertEquals(1,existing.size(),"One source per endpoint contract");
            var row=existing.getFirst();
            assertEquals("DISASTER",row.get("source_kind"),"Existing source contract must match");
            assertEquals(Boolean.TRUE,row.get("active"),"Existing source must already be active");
            return (UUID)row.get("source_id");
        }
        UUID source=UUID.randomUUID();
        sql.update("insert into data_source(source_id,name,source_kind,endpoint_key,collection_interval_minutes,active) values(?,?,'DISASTER',?,5,true)",
            source,"DEVELOPMENT PARTIAL PROBE",provider.endpointKey);
        return source;
    }
    private List<Sample> samples(OfficialProviderClient.Provider provider){
        Assumptions.assumeTrue(observed.containsKey(provider),"Provider observation was not available");
        return observed.get(provider);
    }
    @ParameterizedTest @EnumSource(OfficialProviderClient.Provider.class) @Order(1)
    void aConnectionAndResponseContract(OfficialProviderClient.Provider provider){
        Assumptions.assumeTrue("true".equals(System.getenv("EODIISSEO_"+provider.name()+"_APPROVED")),"Dataset approval required");
        String key=System.getenv(provider==OfficialProviderClient.Provider.WILDFIRE_MESSAGES?"EODIISSEO_DEV_MOIS_KEY":"EODIISSEO_DEV_KMA_KEY");
        Assumptions.assumeTrue(key!=null&&!key.isBlank(),"Development key unavailable");
        UUID source=developmentSource(sql,provider);sources.put(provider,source);
        LocalDate day=LocalDate.parse(System.getenv("EODIISSEO_DEV_"+provider.name()+"_DATE"));
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        assertTrue(day.isBefore(today),"Historical query required");
        if(provider==OfficialProviderClient.Provider.HEAVY_RAIN)assertFalse(day.isBefore(today.minusDays(60)),"Confirmed getPwnCd retention: choose a date within 60 days before any GET");
        List<Sample> samples=new ArrayList<>();observed.put(provider,samples);
        int limit=Integer.parseInt(Objects.toString(System.getenv("EODIISSEO_DEV_PROBE_REQUESTS"),"2"));
        assertTrue(limit==1||limit==2,"Finite observation request bound");
        String sequence=Objects.toString(System.getenv("EODIISSEO_DEV_RAIN_PAGE_SEQUENCE"),"1");
        assertTrue(sequence.equals("1")||(provider==OfficialProviderClient.Provider.HEAVY_RAIN&&limit==2&&sequence.equals("1,2")),"Only an explicit finite rain page pair is allowed");
        int[] calls={0};
        try(var http=new OfficialHttp()){
            for(int index=0;index<limit;index++){
                boolean hub=provider==OfficialProviderClient.Provider.EARTHQUAKE
                    && "KMA_HUB".equals(System.getenv("EODIISSEO_DEV_EARTHQUAKE_ROUTE"));
                String actualKey=key;
                if(hub){
                    Assumptions.assumeTrue("true".equals(System.getenv("EODIISSEO_KMA_HUB_APPROVED")),"Explicit Hub approval required");
                    actualKey=System.getenv("EODIISSEO_DEV_KMA_HUB_KEY");
                    Assumptions.assumeTrue(actualKey!=null&&!actualKey.isBlank(),"Hub development key unavailable");
                }
                LocalDate selected=provider==OfficialProviderClient.Provider.WILDFIRE_MESSAGES&&index==0&&limit==2?day.minusDays(1):day;
                int requestedPage=sequence.equals("1,2")?index+1:1;
                int[] status={0};byte[][] raw={null};
                OfficialProviderClient.Transport transport=(uri,timeout)->{
                    if(++calls[0]>limit)throw new CollectionFailure("PROBE_CALL_LIMIT");
                    var reply=http.get(uri,timeout);status[0]=reply.status();raw[0]=reply.body();return reply;
                };
                var client=hub?OfficialProviderClient.earthquakeHub(transport):new OfficialProviderClient(provider,transport);
                OfficialProviderClient.Page page=null;String result="OK";
                try{page=client.page(actualKey,selected,selected,requestedPage,5);}catch(CollectionFailure failure){result=failure.code();}
                catch(RuntimeException failure){result="UNEXPECTED_RESPONSE";}
                if(raw[0]!=null){
                    String text=new String(raw[0],StandardCharsets.UTF_8);
                    if(text.contains(actualKey)||text.contains(java.net.URLEncoder.encode(actualKey,StandardCharsets.UTF_8))){
                        raw[0]=null;page=null;result="CREDENTIAL_ECHO_REJECTED";
                    }
                }
                UUID run=archive(source,raw[0],page,requestedPage);
                var sample=new Sample(provider==OfficialProviderClient.Provider.WILDFIRE_MESSAGES?"MOIS_V2":hub?"KMA_HUB":"PUBLIC_PORTAL",selected,requestedPage,status[0],result,raw[0],page,run);
                samples.add(sample);diagnostics(provider,sample,samples);
                // Do not spend the next request on auth, transport or malformed responses.
                if(!Set.of("OK","NO_DATA").contains(result))break;
                Thread.sleep(1000); // Same bound across separately constructed explicit endpoint contracts.
            }
        }catch(InterruptedException failure){Thread.currentThread().interrupt();fail("Probe interrupted");}
         catch(RuntimeException failure){fail("Development observation failed; confidential details withheld");}
        assertTrue(calls[0]<=limit,"Provider GET bound");
        assertTrue(samples.stream().allMatch(s->s.status()==200&&Set.of("OK","NO_DATA").contains(s.result())),
            "Connection/authentication/schema contract failed; see safe diagnostics");
    }
    @ParameterizedTest @EnumSource(OfficialProviderClient.Provider.class) @Order(2)
    void bActualPublicationExists(OfficialProviderClient.Provider provider){
        assertTrue(samples(provider).stream().anyMatch(s->s.page()!=null&&!s.page().items().isEmpty()),
            "No actual publication rows were obtained; NO_DATA does not pass existence verification");
    }
    @ParameterizedTest @EnumSource(value=OfficialProviderClient.Provider.class,names="HEAVY_RAIN") @Order(2)
    void actualRainFieldCompatibilityWithoutIncidentRegistration(OfficialProviderClient.Provider provider){
        var rows=samples(provider).stream().filter(s->s.page()!=null).flatMap(s->s.page().items().stream()).toList();
        Assumptions.assumeTrue(!rows.isEmpty(),"No actual warning rows for compatibility verification");
        int candidateMatches=0;
        for(var row:rows){
            assertDoesNotThrow(()->OfficialRecordNormalizer.identity(provider,row),"Official publication identity compatibility");
            Instant issued=assertDoesNotThrow(()->OfficialRecordNormalizer.time(row.get("tmFc")),"Official issue timestamp compatibility");
            assertFalse(issued.isAfter(Instant.now()),"Publication timestamp is not in the future");
            assertEquals(LocalDate.parse(System.getenv("EODIISSEO_DEV_HEAVY_RAIN_DATE")),LocalDate.ofInstant(issued,ZoneId.of("Asia/Seoul")),"Returned publication day matches request");
            assertEquals("2",Objects.toString(row.get("warnVar"),""),"Requested rain filter must match returned hazard");
            assertTrue(Set.of("0","1","2").contains(Objects.toString(row.get("warnStress"),"")),"Unknown official warning level");
            assertTrue(Set.of("1","2","3","6","7","8").contains(Objects.toString(row.get("command"),"")),"Unknown warning command");
            assertTrue(Set.of("0","1").contains(Objects.toString(row.get("cancel"),"")),"Unknown cancellation code");
            for(String field:List.of("startTime","endTime","allEndTime")){
                String value=Objects.toString(row.get(field),"");
                assertDoesNotThrow(()->OfficialRecordNormalizer.warningTime(value),"Conditional warning timestamp compatibility; zero remains unavailable");
            }
            if("202609260340".equals(Objects.toString(row.get("tmFc")))&&"111".equals(Objects.toString(row.get("tmSeq"))))candidateMatches++;
        }
        System.out.println("DEV_RAIN_COMPATIBILITY rows="+rows.size()+" candidate_publication_matches="+candidateMatches+" field_contract=COMPATIBLE full_collection_count_consistent="+samples(provider).stream().filter(s->s.page()!=null).allMatch(s->s.page().total()>=s.page().items().size())+" incident=UNCONFIRMED impact=UNKNOWN");
        assertNoIncidentWrites();
    }    @ParameterizedTest @EnumSource(OfficialProviderClient.Provider.class) @Order(3)
    void cExactOriginalStorageAndRollback(OfficialProviderClient.Provider provider){
        for(var sample:samples(provider)){
            assertNotNull(sample.raw(),"Original response unavailable");
            assertEquals(sample.requestedPage(),sql.queryForObject("select page_no from owned_collection_page where run_id=?",Integer.class,sample.run()),"Stored page corresponds to requested page");
            byte[] stored=sql.queryForObject("select raw_body from owned_collection_page where run_id=?",byte[].class,sample.run());
            assertTrue(Arrays.equals(sample.raw(),stored),"Exact wire bytes round trip");
            assertEquals("FAILED",sql.queryForObject("select status from collection_run where run_id=?",String.class,sample.run()),
                "A finite partial probe is not complete collection coverage");
            long before=sql.queryForObject("select count(*) from owned_collection_page",Long.class);
            tx.executeWithoutResult(status->{
                UUID rolledBack=archive(sources.get(provider),sample.raw(),sample.page(),sample.requestedPage());
                assertNotNull(rolledBack,"Rollback exercise stored a temporary original");
                status.setRollbackOnly();
            });
            assertEquals(before,sql.queryForObject("select count(*) from owned_collection_page",Long.class),"Rollback retains previously saved originals only");
        }
        assertNoIncidentWrites();
    }
    @ParameterizedTest @EnumSource(OfficialProviderClient.Provider.class) @Order(4)
    void dUnverifiedPublicationsStopBeforeIncidentPipeline(OfficialProviderClient.Provider provider){
        var samples=samples(provider);
        var db=new OwnedDatabase(sql);var events=new DisasterRuntimeEvents(db);var disasters=new DisasterService(db);
        var incidents=new VerifiedIncidentService(db,disasters,events,Optional.empty());
        var normalizer=new OfficialRecordNormalizer(db,disasters,events,incidents);
        int rows=0;
        for(var sample:samples)if(sample.page()!=null)for(var row:sample.page().items()){
            rows++;
            // Warning publication != a confirmed physical damage incident. No crosswalk/evidence is supplied.
            if(provider==OfficialProviderClient.Provider.HEAVY_RAIN)continue;
            var result=tx.execute(status->normalizer.normalize(sources.get(provider),provider,row,OfficialProviderClient.canonical(row),Instant.now()));
            assertNotNull(result);assertNull(result.sourceRecord(),"Unverified source cannot register an incident");
            assertTrue(Set.of("EARTHQUAKE_UNLINKED","WILDFIRE_MESSAGE_UNLINKED","UNCLASSIFIED_MESSAGE").contains(result.classification()),
                "No guessed incident classification");
        }
        Assumptions.assumeTrue(rows>0,"No actual rows available for stop-policy verification");
        assertNoIncidentWrites();
        var reader=new OfficialDisasterQueryService(db,Clock.systemUTC(),Duration.ofHours(24));
        assertTrue(reader.list(null,null,0,20).isEmpty(),"Unlinked originals must not enter disaster HTTP DTOs");
    }
    @ParameterizedTest @EnumSource(OfficialProviderClient.Provider.class) @Order(5)
    void eVerifiedIncidentContract(OfficialProviderClient.Provider provider){
        samples(provider);
        Assumptions.assumeTrue(false,"Official event identity/announcement relation or complete warning contract not obtained; incident pipeline is unverified");
    }
    private void assertNoIncidentWrites(){
        for(String table:List.of("disaster","source_disaster_revision","disaster_runtime_outbox","summary_runtime_task"))
            assertEquals(0L,sql.queryForObject("select count(*) from "+table,Long.class),"Unverified raw must not create "+table);
        assertEquals(0L,sql.queryForObject("select count(*) from owned_collection_coverage",Long.class),"No partial probe coverage");
        assertEquals(0L,sql.queryForObject("select count(*) from owned_collection_cursor",Long.class),"No cursor advancement");
    }
    private UUID archive(UUID source,byte[] raw,OfficialProviderClient.Page page,int requestedPage){
        if(raw==null)return null;
        UUID run=UUID.randomUUID();
        sql.update("insert into collection_run(run_id,source_id,mode,started_at,finished_at,status,records_received,records_saved,error_code,trigger_type) values(?,?,'LIVE',?,?,'FAILED',?,0,'DEVELOPMENT_PARTIAL_PROBE','MANUAL')",
            run,source,java.sql.Timestamp.from(Instant.now()),java.sql.Timestamp.from(Instant.now()),page==null?0:page.items().size());
        sql.update("insert into owned_collection_page(page_id,run_id,page_no,raw_body,body_sha256,fetched_at) values(?,?,?,?,?,?)",
            UUID.randomUUID(),run,requestedPage,raw,sha(raw),java.sql.Timestamp.from(Instant.now()));
        return run;
    }
    private static void diagnostics(OfficialProviderClient.Provider provider,Sample sample,List<Sample> previous){
        Set<String> fields=new TreeSet<>();Map<String,Set<String>> types=new TreeMap<>();Map<String,Integer> shapes=new TreeMap<>();
        Map<String,Integer> codes=new TreeMap<>();Set<String> ids=new HashSet<>(),past=new HashSet<>();int sameId=0,changedId=0,createdBefore=0,createdOn=0,createdAfter=0,createdUnknown=0;
        Map<String,String> prior=new HashMap<>();
        for(int i=0;i<previous.size()-1;i++)if(previous.get(i).page()!=null)for(var row:previous.get(i).page().items()){
            String id=Objects.toString(row.get("SN"),"");past.add(id);prior.put(id,OfficialProviderClient.canonical(row));
        }
        if(sample.page()!=null)for(var row:sample.page().items()){
            for(var field:row.entrySet())if(field.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]{0,39}")){
                fields.add(field.getKey());types.computeIfAbsent(field.getKey(),k->new TreeSet<>()).add(
                    field.getValue()==null?"NULL":field.getValue() instanceof String?"STRING":field.getValue() instanceof Number?"NUMBER":"OTHER");
            }
            for(String field:List.of("CRT_DT","REG_YMD","MDFCN_YMD","tmFc","tmEqk","startTime","endTime","allEndTime")){
                if(row.containsKey(field))shapes.merge(field+":"+shape(row.get(field)),1,Integer::sum);
            }
            for(String field:List.of("fcTp","warnVar","warnStress","command","cancel")){
                String code=Objects.toString(row.get(field),"");if(code.matches("[0-9]{1,2}"))codes.merge(field+":"+code,1,Integer::sum);
            }
            if(provider==OfficialProviderClient.Provider.WILDFIRE_MESSAGES){
                try{
                    String created=Objects.toString(row.get("CRT_DT"),"");
                    LocalDate date;
                    if(created.matches("[0-9]{14}"))date=LocalDate.parse(created.substring(0,8),java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                    else date=LocalDate.parse(created.substring(0,10).replace('/','-'));
                    if(date.isBefore(sample.day()))createdBefore++;else if(date.equals(sample.day()))createdOn++;else createdAfter++;
                }catch(RuntimeException unsupported){createdUnknown++;}
            }
            String id=Objects.toString(row.get("SN"),"");
            if(!id.isEmpty()){ids.add(id);if(past.contains(id)){sameId++;if(!OfficialProviderClient.canonical(row).equals(prior.get(id)))changedId++;}}
        }
        String headerCode="UNRECOGNIZED",headerMessage="UNRECOGNIZED",wire="UNKNOWN";
        if(sample.raw()!=null){
            String body=new String(sample.raw(),StandardCharsets.UTF_8).strip();wire=body.startsWith("<")?"XML":"JSON";
            try{
                var root=tools.jackson.databind.json.JsonMapper.builder().build().readValue(body,Map.class);
                Object response=root.getOrDefault("response",root);
                if(response instanceof Map<?,?> envelope && envelope.get("header") instanceof Map<?,?> header){
                    String code=Objects.toString(header.get("resultCode"),"");
                    String message=Objects.toString(header.get("resultMsg"),"");
                    if(code.matches("[0-9]{1,3}"))headerCode=code;
                    if(message.matches("[\\p{IsHangul}a-zA-Z0-9_ .()/:-]{1,160}")&&!message.contains("http"))headerMessage=message;
                }
            }catch(RuntimeException unsupported){ /* No provider text in exception output. */ }
        }
        Set<String> priorPublications=new HashSet<>();int repeatedPublications=0;
        if(provider!=OfficialProviderClient.Provider.WILDFIRE_MESSAGES){
            for(int i=0;i<previous.size()-1;i++)if(previous.get(i).page()!=null)for(var row:previous.get(i).page().items()){
                try{priorPublications.add(OfficialRecordNormalizer.identity(provider,row));}catch(IllegalArgumentException ignored){}
            }
            if(sample.page()!=null)for(var row:sample.page().items()){
                try{if(priorPublications.contains(OfficialRecordNormalizer.identity(provider,row)))repeatedPublications++;}catch(IllegalArgumentException ignored){}
            }
        }
        System.out.println("DEV_DISASTER_PROBE provider="+provider+" route="+sample.route()+" day="+sample.day()+" page="+sample.requestedPage()+" http="+sample.status()+
            " result="+sample.result()+" code="+headerCode+" message="+headerMessage+" wire="+wire+
            " rows="+(sample.page()==null?0:sample.page().items().size())+" total="+(sample.page()==null?-1:sample.page().total())+
            " stored_pages="+(sample.run()==null?0:1)+" fields="+fields+" types="+types+" shapes="+shapes+" codes="+codes+
            " created_before="+createdBefore+" created_on="+createdOn+" created_after="+createdAfter+" created_unknown="+createdUnknown+" repeated_SN="+sameId+" changed_same_SN="+changedId+" repeated_publications="+repeatedPublications+" timezone=UNVERIFIED incident=UNLINKED");
    }
    private static String shape(Object value){
        if(value==null)return "NULL";String text=value.toString();if(text.isEmpty())return "EMPTY";
        if(text.matches("[0-9]{14}"))return "COMPACT14";
        if(text.matches("[0-9]{12}"))return "COMPACT12";
        if(text.matches("[0-9]{8}"))return "COMPACT8";
        if(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))return "LOCAL_SECONDS";
        if(text.matches("[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))return "SLASH_LOCAL_SECONDS";
        if(text.length()<=40&&text.matches("[0-9TtZz +/.:_-]+"))return "MASKED_"+text.replaceAll("[0-9]","#").replaceAll("[A-Za-z]","A");
        return "OTHER";
    }
    private static String sha(byte[] body){
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));}
        catch(Exception failure){throw new IllegalStateException("SHA-256 unavailable");}
    }
}

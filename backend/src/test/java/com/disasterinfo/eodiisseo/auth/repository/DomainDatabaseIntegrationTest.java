package com.disasterinfo.eodiisseo.auth.repository;

import com.disasterinfo.eodiisseo.area.dto.AdministrativeAreaInput;
import com.disasterinfo.eodiisseo.area.repository.AreaRepository;
import com.disasterinfo.eodiisseo.area.service.*;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.collection.client.CollectionClient;
import com.disasterinfo.eodiisseo.collection.domain.CollectedRecord;
import com.disasterinfo.eodiisseo.collection.repository.CollectionRepository;
import com.disasterinfo.eodiisseo.collection.service.*;
import com.disasterinfo.eodiisseo.disaster.service.DisasterService;
import com.disasterinfo.eodiisseo.location.repository.LocationRepository;
import com.disasterinfo.eodiisseo.location.service.LocationService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.disasterinfo.eodiisseo.auth.repository.CanonicalDatabaseIntegrationTest.*;

@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
class DomainDatabaseIntegrationTest {
    static final UUID MESSAGE=UUID.fromString("20000000-0000-0000-0000-000000000005");
    static final UUID QUAKE=UUID.fromString("20000000-0000-0000-0000-000000000003");
    @BeforeAll static void start(){createDatabase();}
    @AfterAll static void stop(){dropOnlyCreatedDatabase();}
    private LocationService locations(){return new LocationService(new LocationRepository(db),clock);}
    private AreaService areas(){return new AreaService(new AreaRepository(db),clock);}
    private CollectionRecordWriter writer(){return new CollectionRecordWriter(db,new ObjectMapper(),new DisasterService(db));}
    private CollectionService collections(CollectionClient client){return new CollectionService(new CollectionRepository(db),client,writer(),manager,clock,true);}
    private String region(){String code=UUID.randomUUID().toString().substring(0,8);db.update("insert into admin_area(area_code,name,level,active) values(?,'test region','SIGUNGU',true)",code);return code;}

    @Test void locationRequiresConsentAndRevocationClearsAllGroups(){
        UUID actor=member(),group=UUID.randomUUID();var service=locations();
        assertEquals(403,assertThrows(ApiProblem.class,()->tx.execute(s->service.update(actor,37,127))).status());
        db.update("insert into safety_group values(?,'test','OTHER',? ,?,?)",group,actor,UUID.randomUUID().toString().substring(0,20),clock.instant());
        db.update("insert into group_member values(?,?,'ADMIN',true,?)",group,actor,clock.instant());
        tx.execute(s->service.consent(actor,true));tx.execute(s->service.update(actor,37,127));tx.execute(s->service.update(actor,38,128));
        assertEquals(1,db.count("select count(*) from member_location where member_id=?",actor));
        assertEquals(128.0,((Number)db.one("select ST_X(point::geometry) x from member_location where member_id=?",actor).get("x")).doubleValue());
        tx.execute(s->service.consent(actor,false));
        assertEquals(0,db.count("select count(*) from member_location where member_id=?",actor));
        assertEquals(0,db.count("select count(*) from group_member where member_id=? and location_sharing_enabled",actor));
        tx.execute(s->service.consent(actor,true));
        assertEquals(0,db.count("select count(*) from group_member where member_id=? and location_sharing_enabled",actor));
    }
    @Test void revokeAndGpsUpdateCannotLeaveCoordinatesWithoutConsent() throws Exception {
        UUID actor=member();var service=locations();tx.execute(s->service.consent(actor,true));
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->tx.execute(s->service.consent(actor,false)));
            var b=pool.submit(()->{try{tx.execute(s->service.update(actor,37,127));}catch(ApiProblem expected){assertEquals(403,expected.status());}});
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(0,db.count("select count(*) from member_location where member_id=?",actor));
    }
    @Test void interestAreaOwnershipAndLimitsAreAtomic() throws Exception {
        UUID actor=member(),other=member();var service=areas();
        for(int i=0;i<4;i++){String code=region();tx.execute(s->service.create(actor,code,"test"));}
        var code1=region();var code2=region();
        try(var pool=Executors.newFixedThreadPool(2)){
            List<Future<Boolean>> results=new ArrayList<>();
            for(String code:List.of(code1,code2))results.add(pool.submit(()->{try{tx.execute(s->service.create(actor,code,"test"));return true;}catch(ApiProblem e){assertEquals(409,e.status());return false;}}));
            int successes=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);
        }
        assertEquals(5,db.count("select count(*) from interest_area where member_id=?",actor));
        UUID id=(UUID)db.one("select interest_area_id from interest_area where member_id=? limit 1",actor).get("interest_area_id");
        assertEquals(403,assertThrows(ApiProblem.class,()->tx.execute(s->{service.delete(other,id);return null;})).status());
        assertEquals(400,assertThrows(ApiProblem.class,()->tx.execute(s->service.create(other,"not-registered","invalid"))).status());
        assertEquals(0,((List<?>)service.list(other).get("interestAreas")).size());
    }
    @Test void administrativeImportKeepsProvenanceAndRejectsOldVersions(){
        String code=UUID.randomUUID().toString().substring(0,8);var service=new AdministrativeAreaImportService(db,new ObjectMapper(),clock);
        Instant current=clock.instant().minusSeconds(100);
        var polygon=Map.<String,Object>of("type","Polygon","coordinates",List.of(List.of(List.of(126,37),List.of(127,37),List.of(127,38),List.of(126,38),List.of(126,37))));
        var input=new AdministrativeAreaInput(code,"test area","SIGUNGU",null,true,"https://example.test/official-dataset","TEST_FIXTURE",code,2026,current,37.5,126.5,polygon);
        int imported=tx.execute(s->service.importAreas(List.of(input)));assertEquals(1,imported);
        assertNotNull(db.one("select boundary from admin_area where area_code=?",code).get("boundary"));
        var older=new AdministrativeAreaInput(code,"test area","SIGUNGU",null,true,input.sourceUrl(),input.sourceProvider(),code,2026,current.minusSeconds(1),null,null,null);
        assertEquals(409,assertThrows(ApiProblem.class,()->tx.execute(s->service.importAreas(List.of(older)))).status());
    }
    @Test void rawMessagesAreVersionedWithoutInventingDisasters(){
        var writer=writer();String key=UUID.randomUUID().toString();long before=db.count("select count(*) from disaster");
        var first=new CollectedRecord(key,Map.of("SN",key,"CRT_DT","2026-11-02 10:00:00","MSG_CN","[TEST] earthquake message","RCPTN_RGN_NM","test recipients"));
        tx.execute(s->writer.save(MESSAGE,"SAFETYDATA_DISASTER_MESSAGE",List.of(first),clock.instant()));
        tx.execute(s->writer.save(MESSAGE,"SAFETYDATA_DISASTER_MESSAGE",List.of(first),clock.instant()));
        UUID id=(UUID)db.one("select record_id from collection_raw_record where source_id=? and source_record_key=?",MESSAGE,key).get("record_id");
        assertEquals(1,db.count("select count(*) from collection_raw_revision where record_id=?",id));
        var edited=new CollectedRecord(key,Map.of("SN",key,"MSG_CN","[TEST] correction"));
        tx.execute(s->writer.save(MESSAGE,"SAFETYDATA_DISASTER_MESSAGE",List.of(edited),clock.instant()));
        assertEquals(2,db.count("select count(*) from collection_raw_revision where record_id=?",id));
        assertEquals(before,db.count("select count(*) from disaster"));
    }
    @Test void earthquakeProjectionUsesOccurrenceAndDoesNotInventSeverityOrImpact(){
        String key=UUID.randomUUID().toString();var record=new CollectedRecord(key,Map.of("tmEqk","20261030120000","tmFc","202610301210","lat",37.0,"lon",127.0,"loc","[TEST] location","mt",3.0));
        tx.execute(s->writer().save(QUAKE,"KMA_EARTHQUAKE",List.of(record),clock.instant()));
        var source=db.one("select * from source_disaster where source_id=? and source_record_key=?",QUAKE,key);UUID disaster=(UUID)source.get("disaster_id");
        var row=db.one("select * from disaster where disaster_id=?",disaster);
        assertEquals(Instant.parse("2026-10-30T03:00:00Z"),OwnedDatabase.instant(row.get("occurred_at")));
        assertNull(row.get("official_severity_id"));assertEquals(0,db.count("select count(*) from disaster_area where disaster_id=?",disaster));
    }
    @Test void durableWorkerRejectsDuplicatesAndDoesNotReplaceGoodDataOnFailure(){
        CollectionClient client=mock(CollectionClient.class);when(client.configuredSources()).thenReturn(Set.of("SAFETYDATA_DISASTER_MESSAGE"));
        when(client.fetch(anyString(),any())).thenReturn(List.of(new CollectedRecord(UUID.randomUUID().toString(),Map.of("MSG_CN","[TEST]"))));
        var service=collections(client);var accepted=service.request(member(),"SAFETYDATA_DISASTER_MESSAGE","LIVE",false);
        assertEquals(409,assertThrows(ApiProblem.class,()->service.request(member(),"SAFETYDATA_DISASTER_MESSAGE","LIVE",false)).status());
        service.workOne();assertEquals("SUCCESS",db.one("select status from collection_run where run_id=?",accepted.refreshRequestId()).get("status"));
        long saved=db.count("select count(*) from collection_raw_record where source_id=?",MESSAGE);
        when(client.fetch(anyString(),any())).thenThrow(new IllegalStateException("test provider failure"));
        var failed=service.request(member(),"SAFETYDATA_DISASTER_MESSAGE","LIVE",false);service.workOne();
        assertEquals("FAILED",db.one("select status from collection_run where run_id=?",failed.refreshRequestId()).get("status"));
        assertEquals(saved,db.count("select count(*) from collection_raw_record where source_id=?",MESSAGE));
        assertEquals(1,db.count("select count(*) from service_error_log where run_id=?",failed.refreshRequestId()));
    }
    @Test void expiredLeaseIsFailedAndReplacedWithoutLateWrites(){
        CollectionClient client=mock(CollectionClient.class);when(client.configuredSources()).thenReturn(Set.of("SAFETYDATA_DISASTER_MESSAGE"));
        var service=collections(client);var first=service.request(member(),"SAFETYDATA_DISASTER_MESSAGE","LIVE",false);
        db.update("update collection_job_lock set lease_until=? where run_id=?",clock.instant().minusSeconds(1),first.refreshRequestId());
        var second=service.request(member(),"SAFETYDATA_DISASTER_MESSAGE","LIVE",false);
        assertNotEquals(first.refreshRequestId(),second.refreshRequestId());
        assertEquals("FAILED",db.one("select status from collection_run where run_id=?",first.refreshRequestId()).get("status"));
        // Leave no pending test job for another test method.
        when(client.fetch(anyString(),any())).thenReturn(List.of());service.workOne();
    }
    @Test void disasterAdapterUsesOfficialImpactPriorityAndKeepsUnknownSeverity(){
        UUID actor=member();tx.execute(s->locations().consent(actor,true));tx.execute(s->locations().update(actor,37.5,126.5));
        String key=UUID.randomUUID().toString();var record=new CollectedRecord(key,Map.of("tmEqk","20261030120000","lat",37.5,"lon",126.5,"loc","[TEST]"));
        tx.execute(s->writer().save(QUAKE,"KMA_EARTHQUAKE",List.of(record),clock.instant()));
        var src=db.one("select * from source_disaster where source_id=? and source_record_key=?",QUAKE,key);UUID disaster=(UUID)src.get("disaster_id");
        var reader=new com.disasterinfo.eodiisseo.disaster.adapter.NormalizedDisasterReader(db,new com.disasterinfo.eodiisseo.group.repository.GroupRepository(db),new com.disasterinfo.eodiisseo.area.adapter.AreaNotificationInterestAreas(db),clock,300);
        var query=new com.disasterinfo.eodiisseo.dashboard.DashboardQuery(null,null,null).validate();
        assertNull(reader.findOfficial(disaster).severity());
        assertEquals(com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.Relevance.UNKNOWN,reader.findCandidates(actor,query,null).stream().filter(c->c.disaster().disasterId().equals(disaster)).findFirst().orElseThrow().relevance());
        String known=region(),unknown=region();
        db.update("update admin_area set boundary=ST_GeomFromText('MULTIPOLYGON(((126 37,127 37,127 38,126 38,126 37)))',4326) where area_code=?",known);
        db.update("insert into disaster_area values(?,?,?,'IMPACT','SOURCE_ADDRESS',?,?)",UUID.randomUUID(),disaster,known,src.get("source_disaster_id"),clock.instant());
        assertEquals(com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.Relevance.RELATED,reader.findCandidates(actor,query,null).stream().filter(c->c.disaster().disasterId().equals(disaster)).findFirst().orElseThrow().relevance());
        db.update("insert into disaster_area values(?,?,?,'IMPACT','OFFICIAL',?,?)",UUID.randomUUID(),disaster,unknown,src.get("source_disaster_id"),clock.instant());
        assertEquals(com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader.Relevance.UNKNOWN,reader.findCandidates(actor,query,null).stream().filter(c->c.disaster().disasterId().equals(disaster)).findFirst().orElseThrow().relevance());
    }
}

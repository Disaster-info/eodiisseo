package com.disasterinfo.eodiisseo.auth.repository;

import com.disasterinfo.eodiisseo.collection.client.PublicCollectionClient;
import com.disasterinfo.eodiisseo.collection.repository.CollectionRepository;
import com.disasterinfo.eodiisseo.collection.service.*;
import com.disasterinfo.eodiisseo.disaster.service.DisasterService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.disasterinfo.eodiisseo.auth.repository.CanonicalDatabaseIntegrationTest.*;

/** Opt-in real provider pagination -> worker -> disposable PostGIS. Never logs credentials/payloads. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LIVE_COLLECTION_TESTS",matches="true")
class LiveCollectionDatabaseIntegrationTest {
    @BeforeAll static void start(){createDatabase();}
    @AfterAll static void stop(){dropOnlyCreatedDatabase();}
    @Test void allConfiguredSourcesCommitCompleteSnapshots(){
        var env=new MockEnvironment();System.getenv().forEach(env::setProperty);
        var json=new ObjectMapper();var client=new PublicCollectionClient(env,json,clock);
        assertEquals(4,client.configuredSources().size(),"All four provider credentials are required for this opt-in test");
        var service=new CollectionService(new CollectionRepository(db),client,new CollectionRecordWriter(db,json,new DisasterService(db)),manager,clock,true);
        var batch=service.request(null,null,"LIVE",false);
        for(int i=0;i<4;i++)service.workOne();
        for(String endpoint:client.configuredSources().stream().sorted().toList()){
            var row=db.one("select r.status,r.records_received,r.records_saved,r.error_code from collection_run r join data_source s using(source_id) where r.batch_id=? and s.endpoint_key=?",batch.refreshRequestId(),endpoint);
            assertEquals("SUCCESS",row.get("status"),endpoint+" must commit every page: "+row.get("error_code"));
            System.out.println("Collection verified: "+endpoint+" received="+row.get("records_received")+" saved="+row.get("records_saved"));
        }
        assertEquals("SUCCESS",db.one("select status from collection_batch where batch_id=?",batch.refreshRequestId()).get("status"));
        assertEquals(0,db.count("select count(*) from collection_job_lock"));
        assertTrue(db.count("select count(*) from shelter where active")>0);
    }
}

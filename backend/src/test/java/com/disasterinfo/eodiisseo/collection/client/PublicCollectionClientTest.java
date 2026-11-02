package com.disasterinfo.eodiisseo.collection.client;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PublicCollectionClientTest {
    PublicCollectionClient client;MockRestServiceServer server;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();client=new PublicCollectionClient(new MockEnvironment().withProperty("KMA_PUBLIC_SERVICE_KEY","test-only").withProperty("SAFETYDATA_DISASTER_MESSAGE_KEY","test-only"),json,Clock.systemUTC(),builder.build());}
    String page(int total,Map<String,Object> item){return json.writeValueAsString(Map.of("header",Map.of("resultCode","00"),"totalCount",total,"body",List.of(item)));}
    @Test void readsAllPagesAndKeepsMessageCreationAsRaw(){
        server.expect(queryParam("pageNo","1")).andRespond(withSuccess(page(2,Map.of("SN",1,"CRT_DT","2026-11-02 10:00:00","RCPTN_RGN_NM","[TEST] recipients")),MediaType.APPLICATION_JSON));
        server.expect(queryParam("pageNo","2")).andRespond(withSuccess(page(2,Map.of("SN",2,"MSG_CN","[TEST]")),MediaType.APPLICATION_JSON));
        AtomicInteger heartbeats=new AtomicInteger();var result=client.fetch("SAFETYDATA_DISASTER_MESSAGE",heartbeats::incrementAndGet);
        assertEquals(2,result.size());assertEquals(2,heartbeats.get());assertFalse(result.getFirst().payload().containsKey("occurredAt"));server.verify();
    }
    @Test void providerFailureIsNotAnEmptySuccess(){
        server.expect(anything()).andRespond(withSuccess("{\"header\":{\"resultCode\":\"30\"}}",MediaType.APPLICATION_JSON));
        assertEquals(502,assertThrows(ApiProblem.class,()->client.fetch("SAFETYDATA_DISASTER_MESSAGE",()->{})).status());server.verify();
    }
    @Test void duplicatedPagesAndChangingTotalsAreRejected(){
        server.expect(anything()).andRespond(withSuccess(page(2,Map.of("SN",1)),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(page(2,Map.of("SN",1)),MediaType.APPLICATION_JSON));
        assertThrows(ApiProblem.class,()->client.fetch("SAFETYDATA_DISASTER_MESSAGE",()->{}));server.verify();
    }
    @Test void kmaExplicitNoDataIsSuccessfulEmpty(){
        server.expect(anything()).andRespond(withSuccess("{\"response\":{\"header\":{\"resultCode\":\"03\"}}}",MediaType.APPLICATION_JSON));
        assertTrue(client.fetch("KMA_EARTHQUAKE",()->{}).isEmpty());server.verify();
    }
}

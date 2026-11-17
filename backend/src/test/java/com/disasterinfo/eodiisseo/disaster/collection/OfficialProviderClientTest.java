package com.disasterinfo.eodiisseo.disaster.collection;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import static org.assertj.core.api.Assertions.*;
import static com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.*;

class OfficialProviderClientTest {
    private static final LocalDate DAY=LocalDate.of(2026,11,1);
    private OfficialProviderClient client(OfficialProviderClient.Provider provider,String body){
        return new OfficialProviderClient(provider,(uri,timeout)->new OfficialHttp.Reply(200,body.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void earthquakeJsonAndXmlHaveSameFactsAndPreserveExactWire() {
        String json="{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"pageNo\":1,\"totalCount\":1,\"items\":{\"item\":[{\"tmEqk\":\"20261101120000\",\"lat\":\"37.1\",\"lon\":\"127.1\"}]}}}}";
        String xml="<response><header><resultCode>00</resultCode></header><body><pageNo>1</pageNo><totalCount>1</totalCount><items><item><tmEqk>20261101120000</tmEqk><lat>37.1</lat><lon>127.1</lon></item></items></body></response>";
        var first=client(EARTHQUAKE,json).page("test key",DAY,DAY,1);
        var second=client(EARTHQUAKE,xml).page("test key",DAY,DAY,1);
        assertThat(first.items()).isEqualTo(second.items());assertThat(first.raw()).isEqualTo(json.getBytes(StandardCharsets.UTF_8));
    }
    @Test void fixedEndpointsAndSecretEncodedExactlyOnce(){
        AtomicReference<URI> request=new AtomicReference<>();
        var client=new OfficialProviderClient(HEAVY_RAIN,(uri,timeout)->{request.set(uri);return new OfficialHttp.Reply(200,"{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0,\"items\":\"\"}}}".getBytes(StandardCharsets.UTF_8));});
        client.page("not-a-real-key+/=",DAY,DAY,1);
        assertThat(request.get().getHost()).isEqualTo("apis.data.go.kr");
        assertThat(request.get().getRawQuery()).contains("serviceKey=not-a-real-key%2B%2F%3D","warningType=2","stnId=108","fromTmFc=20261101");
    }
    @Test void messageIdIsNotAnIncidentIdentity(){
        String json="{\"header\":{\"resultCode\":\"00\"},\"totalCount\":1,\"body\":[{\"SN\":\"123\",\"DST_SE_NM\":\"산불\",\"MSG_CN\":\"TEST 산불 예방 안내\",\"CRT_DT\":\"2026-11-01 09:00:00\",\"RCPTN_RGN_NM\":\"TEST\"}]}";
        assertThat(client(WILDFIRE_MESSAGES,json).page("test",DAY,DAY,1).items().getFirst()).containsEntry("SN","123");
    }
    @ParameterizedTest @ValueSource(strings={"","{}","null","not json","{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":1,\"items\":\"\"}}}","<response>","<!DOCTYPE response [<!ENTITY x SYSTEM 'file:///test'>]><response>&x;</response>"})
    void malformedEmptyAndEntityPayloadsFailClosed(String body){
        assertThatThrownBy(()->client(EARTHQUAKE,body).page("test",DAY,DAY,1)).isInstanceOf(CollectionFailure.class);
    }
    @ParameterizedTest @ValueSource(strings={"20","30","31","32","22","23","99"})
    void providerErrorIsNeverSuccessfulOrLeaked(String code){
        String json="{\"response\":{\"header\":{\"resultCode\":\""+code+"\",\"resultMsg\":\"SECRET TEST DETAIL\"}}}";
        assertThatThrownBy(()->client(EARTHQUAKE,json).page("test",DAY,DAY,1)).isInstanceOf(CollectionFailure.class).hasMessageNotContaining("SECRET");
    }
    @ParameterizedTest @ValueSource(ints={401,403,429,500,503})
    void httpFailuresDoNotRetryImmediately(int status){
        var calls=new AtomicInteger();var client=new OfficialProviderClient(EARTHQUAKE,(uri,timeout)->{calls.incrementAndGet();return new OfficialHttp.Reply(status,new byte[0]);});
        assertThatThrownBy(()->client.page("test",DAY,DAY,1)).isInstanceOf(CollectionFailure.class);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void invalidWindowAndKeyNeverSend(){
        var client=new OfficialProviderClient(EARTHQUAKE,(uri,timeout)->{throw new AssertionError("Unexpected network call");});
        assertThatThrownBy(()->client.page("",DAY,DAY,1)).isInstanceOf(CollectionFailure.class);
        assertThatThrownBy(()->client.page("test",DAY,DAY.plusDays(3),1)).isInstanceOf(CollectionFailure.class);
        assertThatThrownBy(()->client.page("test",DAY,DAY,101)).isInstanceOf(CollectionFailure.class);
    }
    @Test void realHttpTransportUsesLoopbackOnlyForTestAndRejectsRedirectAndSlowBody()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/ok",exchange->{byte[] bytes="test body".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/ok");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/slow",exchange->{exchange.sendResponseHeaders(200,100);try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();});
        server.start();
        try(var http=new OfficialHttp()){
            URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            assertThat(new String(http.get(base.resolve("/ok"),Duration.ofSeconds(2)).body(),StandardCharsets.UTF_8)).isEqualTo("test body");
            assertThat(http.get(base.resolve("/redirect"),Duration.ofSeconds(2)).status()).isEqualTo(302);
            assertThatThrownBy(()->http.get(base.resolve("/slow"),Duration.ofMillis(100))).isInstanceOf(CollectionFailure.class);
        }finally{server.stop(0);}
    }
    @Test void strictKstAndAnnouncementIdentities(){
        assertThat(OfficialRecordNormalizer.time("20261101120000")).isEqualTo(Instant.parse("2026-11-01T03:00:00Z"));
        assertThatThrownBy(()->OfficialRecordNormalizer.time("202602301200")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OfficialRecordNormalizer.identity(EARTHQUAKE,Map.of("tmEqk","20261101120000"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void gatewayXmlAuthenticationFailureDoesNotExposeBody(){
        String body="<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode><returnAuthMsg>TEST SECRET</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>";
        assertThatThrownBy(()->client(EARTHQUAKE,body).page("test",DAY,DAY,1)).isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("AUTH_REJECTED")).hasMessageNotContaining("SECRET");
    }
    @Test void changedOrUnboundedPageMetadataIsRejected(){
        for(String body:List.of(
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"pageNo\":2,\"totalCount\":0,\"items\":\"\"}}}",
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"pageNo\":1,\"totalCount\":10001,\"items\":\"\"}}}")){
            assertThatThrownBy(()->client(EARTHQUAKE,body).page("test",DAY,DAY,1)).isInstanceOf(CollectionFailure.class);
        }
    }
    @Test void explicitProbeRowsAreBoundedAndDefaultContractRemainsOneHundred(){
        for(int rows:new int[]{5,100}){
            AtomicReference<URI> request=new AtomicReference<>();
            var client=new OfficialProviderClient(EARTHQUAKE,(uri,timeout)->{request.set(uri);return new OfficialHttp.Reply(200,"{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0,\"items\":\"\"}}}".getBytes(StandardCharsets.UTF_8));});
            if(rows==100)client.page("synthetic-key",DAY,DAY,1);else client.page("synthetic-key",DAY,DAY,1,rows);
            assertThat(request.get().getRawQuery()).contains("numOfRows="+rows);
        }
    }
    @ParameterizedTest @ValueSource(ints={-1,0,101,Integer.MAX_VALUE})
    void invalidProbeSizeNeverSends(int rows){
        var client=new OfficialProviderClient(EARTHQUAKE,(uri,timeout)->{throw new AssertionError("No request allowed");});
        assertThatThrownBy(()->client.page("synthetic-key",DAY,DAY,1,rows))
            .isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("ROW_LIMIT"));
    }
    @Test void providerCannotReturnMoreRowsThanTheExplicitProbeBound(){
        String body="{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":2,\"items\":{\"item\":[{},{}]}}}}";
        assertThatThrownBy(()->client(EARTHQUAKE,body).page("synthetic-key",DAY,DAY,1,1))
            .isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("ROW_LIMIT"));
    }
    @Test void noDataCodeDoesNotBecomeSuccessfulCoverageOrAnIncident(){
        String body="{\"response\":{\"header\":{\"resultCode\":\"03\",\"resultMsg\":\"NODATA_ERROR\"}}}";
        for(var provider:new OfficialProviderClient.Provider[]{EARTHQUAKE,HEAVY_RAIN}){
            assertThatThrownBy(()->client(provider,body).page("synthetic-key",DAY,DAY,1,5))
                .isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("NO_DATA"));
        }
    }
    @Test void conditionalWarningZeroIsUnavailableButMandatoryIssueTimeRemainsStrict(){
        for(Object value:Arrays.asList(null,"",0,0L,"0"))assertThat(OfficialRecordNormalizer.warningTime(value)).isEmpty();
        assertThat(OfficialRecordNormalizer.warningTime(202609260340L)).contains(Instant.parse("2026-09-25T18:40:00Z"));
        assertThatThrownBy(()->OfficialRecordNormalizer.time(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OfficialRecordNormalizer.warningTime("202602300100")).isInstanceOf(IllegalArgumentException.class);
    }
}

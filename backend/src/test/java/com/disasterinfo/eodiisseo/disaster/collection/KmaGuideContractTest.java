package com.disasterinfo.eodiisseo.disaster.collection;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.*;
/** Official KMA guide examples (checked 2026-11-03) differ from the portal resultCode=00 table. */
class KmaGuideContractTest {
    @Test void documentedZeroNormalServiceIsAcceptedForKmaOnly(){
        String body="<response><header><resultCode>0</resultCode><resultMsg>NORMAL_SERVICE</resultMsg></header><body><pageNo>1</pageNo><totalCount>0</totalCount><items/></body></response>";
        for(var provider:new OfficialProviderClient.Provider[]{EARTHQUAKE,HEAVY_RAIN}){
            var client=new OfficialProviderClient(provider,(uri,timeout)->{throw new AssertionError("No transport");});
            assertThat(client.decode(body.getBytes(StandardCharsets.UTF_8),1).items()).isEmpty();
        }
        var messages=new OfficialProviderClient(WILDFIRE_MESSAGES,(uri,timeout)->{throw new AssertionError("No transport");});
        assertThatThrownBy(()->messages.decode(body.getBytes(StandardCharsets.UTF_8),1)).isInstanceOf(CollectionFailure.class);
    }
    @Test void bareZeroOrUnrecognisedNormalMessageDoesNotExpandTheSuccessContract(){
        var client=new OfficialProviderClient(EARTHQUAKE,(uri,timeout)->{throw new AssertionError("No transport");});
        for(String message:new String[]{"","ERROR","UNKNOWN"}){
            String body="{\"response\":{\"header\":{\"resultCode\":\"0\",\"resultMsg\":\""+message+"\"},\"body\":{\"totalCount\":0,\"items\":\"\"}}}";
            assertThatThrownBy(()->client.decode(body.getBytes(StandardCharsets.UTF_8),1)).isInstanceOf(CollectionFailure.class);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"03","3"})
    void officialKmaNoDataIsDistinctFromAuthenticationAndInvalidSchemas(String code){
        String body="{\"response\":{\"header\":{\"resultCode\":\""+code+"\",\"resultMsg\":\"NODATA_ERROR\"}}}";
        for(var provider:new OfficialProviderClient.Provider[]{EARTHQUAKE,HEAVY_RAIN}){
            var client=new OfficialProviderClient(provider,(uri,timeout)->{throw new AssertionError("No transport");});
            assertThatThrownBy(()->client.decode(body.getBytes(StandardCharsets.UTF_8),1))
                .isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("NO_DATA"));
        }
        var messages=new OfficialProviderClient(WILDFIRE_MESSAGES,(uri,timeout)->{throw new AssertionError("No transport");});
        assertThatThrownBy(()->messages.decode(body.getBytes(StandardCharsets.UTF_8),1))
            .isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("PROVIDER_CODE"));
    }
    @Test void unknownMessageWithNoDataNumberIsNotTrusted(){
        var client=new OfficialProviderClient(EARTHQUAKE,(uri,timeout)->{throw new AssertionError("No transport");});
        assertThatThrownBy(()->client.decode("{\"response\":{\"header\":{\"resultCode\":\"03\",\"resultMsg\":\"UNKNOWN\"}}}".getBytes(StandardCharsets.UTF_8),1))
            .isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("PROVIDER_CODE"));
    }
    @Test void explicitHubUsesDocumentedFixedEndpointAndOnlyAuthKey(){
        java.util.concurrent.atomic.AtomicReference<java.net.URI> request=new java.util.concurrent.atomic.AtomicReference<>();
        var hub=OfficialProviderClient.earthquakeHub((uri,timeout)->{request.set(uri);return new OfficialHttp.Reply(200,
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0,\"items\":\"\"}}}".getBytes(StandardCharsets.UTF_8));});
        var date=java.time.LocalDate.of(2024,6,12);hub.page("synthetic+/=",date,date,1,5);
        assertThat(request.get().getHost()).isEqualTo("apihub.kma.go.kr");
        assertThat(request.get().getPath()).isEqualTo("/api/typ02/openApi/EqkInfoService/getEqkMsg");
        assertThat(request.get().getRawQuery()).contains("authKey=synthetic%2B%2F%3D","numOfRows=5").doesNotContain("serviceKey=");
    }
}

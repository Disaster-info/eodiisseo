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
}

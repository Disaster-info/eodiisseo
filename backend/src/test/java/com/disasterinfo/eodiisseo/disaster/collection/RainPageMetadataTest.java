package com.disasterinfo.eodiisseo.disaster.collection;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RainPageMetadataTest {
    private OfficialProviderClient.Page page(String metadata,int count,String items){
        String body="{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"pageNo\":1,"+metadata+"\"totalCount\":"+count+",\"items\":{\"item\":"+items+"}}}}";
        var client=new OfficialProviderClient(OfficialProviderClient.Provider.HEAVY_RAIN,
            (uri,timeout)->new OfficialHttp.Reply(200,body.getBytes(StandardCharsets.UTF_8)));
        return client.page("synthetic-key",LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1);
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void contradictoryDeclaredPageSizeFails(int rows){
        assertThatThrownBy(()->page("\"numOfRows\":"+rows+",",2,"[{\"tmSeq\":\"1\"},{\"tmSeq\":\"2\"}]"))
            .isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("PAGE_INCOMPLETE"));
    }
    @Test void partialLastPageDoesNotRequireRowsToEqualDeclaredCapacity(){
        assertThat(page("\"numOfRows\":100,",1,"[{\"tmSeq\":\"1\"}]").items()).hasSize(1);
    }
    @Test void absentOptionalEchoRetainsExistingParsingContract(){
        assertThat(page("",1,"[{\"tmSeq\":\"1\"}]").items()).hasSize(1);
    }
    @Test void observedZeroTotalIsPreservedForCollectionToRejectRatherThanRewritten(){
        var result=page("\"numOfRows\":100,",0,"[{\"tmSeq\":\"1\"}]");
        assertThat(result.total()).isZero();assertThat(result.items()).hasSize(1);
    }
    @Test void normalEmptyEnvelopeIsParsableButDoesNotProveCoverage(){
        assertThat(page("\"numOfRows\":100,",0,"[]").items()).isEmpty();
    }
}

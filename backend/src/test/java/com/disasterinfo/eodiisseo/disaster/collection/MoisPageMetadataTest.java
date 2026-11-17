package com.disasterinfo.eodiisseo.disaster.collection;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
class MoisPageMetadataTest {
    OfficialProviderClient.Page decode(String body){return new OfficialProviderClient(OfficialProviderClient.Provider.WILDFIRE_MESSAGES,
        (uri,timeout)->{throw new AssertionError("decode only");}).decode(body.getBytes(StandardCharsets.UTF_8),1);}
    @ParameterizedTest @ValueSource(strings={"{\"header\":{\"resultCode\":\"00\"},\"totalCount\":0}",
        "{\"header\":{\"resultCode\":\"00\"},\"totalCount\":0,\"body\":null}",
        "{\"header\":{\"resultCode\":\"00\"},\"totalCount\":0,\"body\":\"\"}"})
    void missingOrAmbiguousBodyIsNotAnEmptyDataset(String body){
        assertThatThrownBy(()->decode(body)).isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("SCHEMA"));
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void declaredRowsCannotContradictItems(int rows){
        assertThatThrownBy(()->decode("{\"header\":{\"resultCode\":\"00\"},\"pageNo\":1,\"numOfRows\":"+rows+",\"totalCount\":2,\"body\":[{\"SN\":1},{\"SN\":2}]}"))
            .isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("PAGE_INCOMPLETE"));
    }
    @Test void explicitEmptyArrayIsDecodedWithoutInferringCoverage(){
        assertThat(decode("{\"header\":{\"resultCode\":\"00\"},\"totalCount\":0,\"body\":[]}").items()).isEmpty();
    }
    @Test void invalidRangeIsRejectedBeforeTransport(){
        var calls=new AtomicInteger();var client=new OfficialProviderClient(OfficialProviderClient.Provider.WILDFIRE_MESSAGES,
            (uri,timeout)->{calls.incrementAndGet();throw new AssertionError("No network allowed");});
        var day=LocalDate.of(2026,11,1);
        assertThatThrownBy(()->client.page("synthetic",day,day.plusDays(1),1)).isInstanceOfSatisfying(CollectionFailure.class,e->assertThat(e.code()).isEqualTo("UNSUPPORTED_END_DATE"));
        assertThat(calls.get()).isZero();
    }
}
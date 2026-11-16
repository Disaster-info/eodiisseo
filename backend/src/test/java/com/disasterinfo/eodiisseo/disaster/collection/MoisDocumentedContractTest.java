package com.disasterinfo.eodiisseo.disaster.collection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import static org.assertj.core.api.Assertions.*;
import static com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.*;

/** Synthetic fixtures shaped by the MOIS public apiDataTable documentation checked 2026-11-03; no live calls. */
class MoisDocumentedContractTest {
    private static final String EMPTY="{\"header\":{\"resultCode\":\"00\"},\"totalCount\":0,\"body\":[]}";
    @ParameterizedTest @CsvSource({"2026-01-01,20260101","2024-02-29,20240229","2026-12-31,20261231"})
    void documentedStartDateIsEncodedExactlyWithNoInventedEndOrSortParameter(String date,String expected){
        var uri=new AtomicReference<URI>();
        var client=new OfficialProviderClient(WILDFIRE_MESSAGES,(request,timeout)->{
            uri.set(request);return new OfficialHttp.Reply(200,EMPTY.getBytes(StandardCharsets.UTF_8));
        });
        LocalDate day=LocalDate.parse(date);
        client.page("SYNTHETIC+/=",day,day,2);
        assertThat(uri.get().getHost()).isEqualTo("www.safetydata.go.kr");
        assertThat(uri.get().getPath()).isEqualTo("/V2/api/DSSP-IF-00247");
        assertThat(uri.get().getRawQuery()).contains("crtDt="+expected,"pageNo=2","numOfRows=100","returnType=json","serviceKey=SYNTHETIC%2B%2F%3D")
            .doesNotContain("toTmFc","fromTmFc","endDt","sort","rgnNm");
    }
    @Test void unverifiedEndDateAndNonEightDigitCalendarNeverSend(){
        var client=new OfficialProviderClient(WILDFIRE_MESSAGES,(uri,timeout)->{throw new AssertionError("Unexpected transport");});
        LocalDate day=LocalDate.of(2026,11,1);
        assertThatThrownBy(()->client.page("test",day,day.plusDays(1),1)).isInstanceOfSatisfying(CollectionFailure.class,
            e->assertThat(e.code()).isEqualTo("UNSUPPORTED_END_DATE"));
        for(LocalDate invalid:List.of(LocalDate.of(0,1,1),LocalDate.of(10000,1,1))){
            assertThatThrownBy(()->client.page("test",invalid,invalid,1)).isInstanceOf(CollectionFailure.class);
        }
    }
    @Test void creationRegistrationAndModificationFieldsRemainSeparateAndDoNotGrantEventIdentity(){
        var row=new LinkedHashMap<String,Object>(Map.of("SN","0000000000000000000001","CRT_DT","2026-10-31 23:59:59",
            "REG_YMD","20261101","MDFCN_YMD","20261102","DST_SE_NM","산불","MSG_CN","SYNTHETIC ONLY","RCPTN_RGN_ID","TEST","RCPTN_RGN_NM","TEST REGION"));
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        byte[] body=mapper.writeValueAsBytes(Map.of("header",Map.of("resultCode","00"),"totalCount",1,"body",List.of(row)));
        var client=new OfficialProviderClient(WILDFIRE_MESSAGES,(uri,timeout)->new OfficialHttp.Reply(200,body));
        // Public docs do not define boundary inclusivity or delayed-record rules: do not discard this returned original.
        var result=client.page("test",LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1);
        assertThat(result.items().getFirst()).containsEntry("SN","0000000000000000000001").containsEntry("REG_YMD","20261101")
            .containsEntry("MDFCN_YMD","20261102").containsEntry("CRT_DT","2026-10-31 23:59:59");
        String oldHash=AuthService.hash(OfficialProviderClient.canonical(row));
        row.put("MDFCN_YMD","20261103");
        assertThat(AuthService.hash(OfficialProviderClient.canonical(row))).isNotEqualTo(oldHash);
        assertThat(OfficialRecordNormalizer.identity(WILDFIRE_MESSAGES,row)).isEqualTo("0000000000000000000001");
    }
    @Test void kmaDateWindowContractIsUnchanged(){
        var request=new AtomicReference<URI>();
        var client=new OfficialProviderClient(HEAVY_RAIN,(uri,timeout)->{
            request.set(uri);return new OfficialHttp.Reply(200,"{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0,\"items\":\"\"}}}".getBytes(StandardCharsets.UTF_8));
        });
        client.page("test",LocalDate.of(2026,11,1),LocalDate.of(2026,11,3),1);
        assertThat(request.get().getRawQuery()).contains("fromTmFc=20261101","toTmFc=20261103","warningType=2","stnId=108").doesNotContain("crtDt");
    }
}

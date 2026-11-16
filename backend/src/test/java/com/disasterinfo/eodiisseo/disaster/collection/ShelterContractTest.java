package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic DSSP-IF-10943 checks; no official identity, snapshot, CRS or live availability claim. */
class ShelterContractTest {
    final Request request=new Request(LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1,2,Duration.ofSeconds(2));
    final OfficialCollectionProviderPlugin plugin=new OfficialCollectionProviderPlugin("MOIS_SHELTERS",(c,r)->{throw new AssertionError("No HTTP");});
    Map<String,Object> row(){return new LinkedHashMap<>(Map.of("ARCD","0012345678","ACMDFCLTY_SN","00001","VT_ACMDFCLTY_NM","SYNTHETIC shelter","LA","37.1","LO","127.1","USE_SE_CD","1"));}
    Reply reply(int total,List<Map<String,Object>> rows){return new Reply(200,JsonMapper.builder().build().writeValueAsBytes(Map.of("header",Map.of("resultCode","00"),"totalCount",total,"body",rows)),Duration.ZERO);}
    @ParameterizedTest @ValueSource(strings={"DUPLICATE","TRAILING","ZERO_TOTAL","WRONG_PAGE","ZERO_ROWS","LARGE_ROWS","MISSING_TOTAL"})
    void ambiguousEnvelopeNeverBecomesACompleteFacilityList(String scenario){
        String s=new String(reply(1,List.of(row())).body(),StandardCharsets.UTF_8);
        s=switch(scenario){case "DUPLICATE"->s.replace("\"totalCount\":1","\"totalCount\":0,\"totalCount\":1");case "TRAILING"->s+"{}";case "ZERO_TOTAL"->s.replace("\"totalCount\":1","\"totalCount\":0");case "WRONG_PAGE"->"{\"pageNo\":2,"+s.substring(1);case "ZERO_ROWS"->"{\"numOfRows\":0,"+s.substring(1);case "LARGE_ROWS"->"{\"numOfRows\":3,"+s.substring(1);default->s.replace("\"totalCount\":1,","").replace(",\"totalCount\":1","");};
        Reply wire=new Reply(200,s.getBytes(StandardCharsets.UTF_8),Duration.ZERO);
        assertThrows(InvalidResponse.class,()->plugin.decode(wire,request));
    }
    @ParameterizedTest @ValueSource(strings={"BOOLEAN","OBJECT","ARRAY","FRACTION","MISSING","CONTROL","DELIMITER","TOO_LONG"})
    void unstableSerialIsNotAnIdentity(String scenario){
        var r=row();Object id=switch(scenario){case "BOOLEAN"->true;case "OBJECT"->Map.of("id",1);case "ARRAY"->List.of(1);case "FRACTION"->1.5;case "CONTROL"->"A\nB";case "DELIMITER"->"A:B";case "TOO_LONG"->"12345678901";default->null;};r.put("ACMDFCLTY_SN",id);
        assertThrows(RuntimeException.class,()->plugin.identity(r));assertThrows(InvalidResponse.class,()->plugin.decode(reply(1,List.of(r)),request));
    }
    @Test void regionNamespaceAndExactLeadingZerosArePreserved(){var a=row();var b=row();b.put("ARCD","0098765432");assertNotEquals(plugin.identity(a),plugin.identity(b));assertEquals("0012345678:00001",plugin.identity(a));a.put("ACMDFCLTY_SN",1);assertEquals("0012345678:1",plugin.identity(a));assertFalse(plugin.evidence().stableSnapshot());}
    @Test void unspecifiedEchoDoesNotBecomeAnInventedWireRequirement(){assertEquals(1,plugin.decode(reply(1,List.of(row())),request).items().size());assertFalse(plugin.evidence().incremental());}
    @ParameterizedTest @ValueSource(strings={"MISSING_COORDS","ONE_COORD","ZERO","SWAPPED","PROJECTED","NAN","INFINITY","OBJECT_COORD","NEGATIVE_CAPACITY","FRACTION_CAPACITY","OVERFLOW_CAPACITY","BAD_AREA","NEGATIVE_AREA","ZERO_USE","UNKNOWN_USE","MISSING_USE","OBJECT_NAME","LONG_NAME","OBJECT_ADDRESS"})
    void unsupportedFactsCannotReachTheLegacyWriter(String scenario){
        var r=row();switch(scenario){case "MISSING_COORDS"->{r.remove("LA");r.remove("LO");}case "ONE_COORD"->r.remove("LA");case "ZERO"->r.put("LA","0");case "SWAPPED"->{r.put("LA","127.1");r.put("LO","37.1");}case "PROJECTED"->r.put("LO","953000");case "NAN"->r.put("LA","NaN");case "INFINITY"->r.put("LO","Infinity");case "OBJECT_COORD"->r.put("LA",Map.of("v",37));case "NEGATIVE_CAPACITY"->r.put("VT_ACMD_PSBL_NMPR",-1);case "FRACTION_CAPACITY"->r.put("VT_ACMD_PSBL_NMPR","1.2");case "OVERFLOW_CAPACITY"->r.put("VT_ACMD_PSBL_NMPR","2147483648");case "BAD_AREA"->r.put("FCLTY_AR","NaN");case "NEGATIVE_AREA"->r.put("FCLTY_AR",-1);case "ZERO_USE"->r.put("USE_SE_CD","0");case "UNKNOWN_USE"->r.put("USE_SE_CD","9");case "MISSING_USE"->r.remove("USE_SE_CD");case "OBJECT_NAME"->r.put("VT_ACMDFCLTY_NM",Map.of("name","bad"));case "LONG_NAME"->r.put("VT_ACMDFCLTY_NM","x".repeat(201));default->r.put("RN_DTL_ADRES",List.of("bad"));}
        assertThrows(IllegalArgumentException.class,()->ShelterRecordValidation.validateForProjection(r));
    }
    @Test void validOptionalUnknownFactsAreNotFabricated(){var r=row();ShelterRecordValidation.validateForProjection(r);assertFalse(r.containsKey("VT_ACMD_PSBL_NMPR"));assertFalse(r.containsKey("FCLTY_AR"));}
    @Test void coordinatesDoNotMoveWithoutAnOfficialChangeContract(){var r=row();ShelterRecordValidation.requireSameKnownCoordinates(r,37.1,127.1);r.put("LO","127.10001");assertThrows(IllegalArgumentException.class,()->ShelterRecordValidation.requireSameKnownCoordinates(r,37.1,127.1));}
}

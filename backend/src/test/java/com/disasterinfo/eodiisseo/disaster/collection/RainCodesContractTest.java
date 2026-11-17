package com.disasterinfo.eodiisseo.disaster.collection;
import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort.*;import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;import org.junit.jupiter.params.ParameterizedTest;import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;import static org.junit.jupiter.api.Assertions.*;
/** Synthetic getPwnCd validation, no external calls or official completeness evidence. */
class RainCodesContractTest {
 final Request request=new Request(LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1,2,Duration.ofSeconds(2));
 final Map<String,Object> row=Map.of("stnId","108","tmFc","202611011200","tmSeq","1","areaCode","Z_SYNTHETIC");
 Map<String,Object> body(int total,int size){return new LinkedHashMap<>(Map.of("pageNo",1,"numOfRows",size,"totalCount",total,"items",Map.of("item",List.of(row,row))));}
 Reply reply(Map<String,Object> body){return new Reply(200,JsonMapper.builder().build().writeValueAsBytes(Map.of("response",Map.of("header",Map.of("resultCode","00"),"body",body))),Duration.ZERO);}
 OfficialCollectionProviderPlugin rain(){return new OfficialCollectionProviderPlugin("KMA_RAIN_CODES",(c,r)->{throw new AssertionError("No HTTP");});}
 @ParameterizedTest @ValueSource(strings={"pageNo","numOfRows","totalCount","items"}) void strictRainRequiresDeclaredMetadata(String field){var b=body(2,2);b.remove(field);assertThrows(InvalidResponse.class,()->rain().decode(reply(b),request));}
 @ParameterizedTest @ValueSource(ints={0,1}) void actualRowsNeverReplaceContradictoryTotal(int total){assertThrows(InvalidResponse.class,()->rain().decode(reply(body(total,2)),request));}
 @ParameterizedTest @ValueSource(ints={-1,0,1,3}) void rowDeclarationsCannotContradictRowsOrRequestBudget(int size){assertThrows(InvalidResponse.class,()->rain().decode(reply(body(2,size)),request));}
 @Test void duplicateKeysAndTrailingDocumentCannotSelectAMoreConvenientTotal(){String normal=new String(reply(body(2,2)).body(),StandardCharsets.UTF_8);for(String text:List.of(normal.replace("\"totalCount\":2","\"totalCount\":0,\"totalCount\":2"),normal+"{}"))assertThrows(InvalidResponse.class,()->rain().decode(new Reply(200,text.getBytes(StandardCharsets.UTF_8),Duration.ZERO),request));}
 @ParameterizedTest @ValueSource(strings={"JSON","XML"}) void explicitEmptyRemainsAParsedObservationWithoutApproval(String format){var b=body(0,2);b.put("items",Map.of("item",List.of()));var wire=reply(b);if(format.equals("XML"))wire=new Reply(200,"<response><header><resultCode>00</resultCode></header><body><pageNo>1</pageNo><numOfRows>2</numOfRows><totalCount>0</totalCount><items/></body></response>".getBytes(StandardCharsets.UTF_8),Duration.ZERO);assertTrue(rain().decode(wire,request).items().isEmpty());assertFalse(rain().evidence().emptyResult());assertFalse(rain().evidence().stableSnapshot());}
 @Test void warningListKeepsItsExistingMissingEchoParsingAndRemainsUnapproved(){var warning=new OfficialCollectionProviderPlugin("KMA_WARNING_LIST",(c,r)->{throw new AssertionError("No HTTP");});var b=body(2,2);b.remove("pageNo");b.remove("numOfRows");assertEquals(2,warning.decode(reply(b),request).items().size());assertFalse(warning.evidence().boundedRange());assertFalse(new com.disasterinfo.eodiisseo.collection.operations.CollectionOfficialAdmissionCatalog().productionAdmissible("KMA_WARNING_LIST"));}
 @Test void existingJsonEmptyShapesRemainObservationsOnly(){for(Object empty:List.of("",List.of(),Map.of("item",List.of()))){var b=body(0,2);b.put("items",empty);assertTrue(rain().decode(reply(b),request).items().isEmpty());assertFalse(rain().evidence().emptyResult());}}

}

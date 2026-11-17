package com.disasterinfo.eodiisseo.disaster.collection;
import org.junit.jupiter.params.ParameterizedTest;import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
/** Synthetic wire checks, never production approval or snapshot/incident proof. */
class EarthquakeResponseContractTest {
 static final String ROW="{\"stnId\":\"108\",\"tmFc\":\"202611011201\",\"tmSeq\":\"1\",\"fcTp\":\"3\"}";
 OfficialProviderClient client(boolean hub){OfficialProviderClient.Transport none=(u,t)->{throw new AssertionError("No network");};return hub?OfficialProviderClient.earthquakeHub(none):new OfficialProviderClient(OfficialProviderClient.Provider.EARTHQUAKE,none);}
 String envelope(int total,String items,String extra){return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"OK\"},\"body\":{\"pageNo\":1,\"totalCount\":"+total+extra+",\"items\":"+items+"}}}";}
 byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 @ParameterizedTest @ValueSource(booleans={false,true}) void contradictoryTotalsAreNeverDecodedAsSuccessfulPage(boolean hub){for(var s:List.of(envelope(0,"{\"item\":["+ROW+"]}",""),envelope(1,"{\"item\":["+ROW+","+ROW+"]}","")))assertThrows(CollectionFailure.class,()->client(hub).decode(bytes(s),1));}
 @ParameterizedTest @ValueSource(booleans={false,true}) void declaredPageSizeCannotContradictReceivedItems(boolean hub){for(String size:List.of("0","-1","1")){String s=envelope(2,"{\"item\":["+ROW+","+ROW+"]}",",\"numOfRows\":"+size);assertThrows(CollectionFailure.class,()->client(hub).decode(bytes(s),1));}}
 @ParameterizedTest @ValueSource(booleans={false,true}) void ambiguousJsonCannotChooseOneTotalOrIgnoreTrailingDocument(boolean hub){String normal=envelope(0,"\"\"","");for(String s:List.of(normal.replace("\"totalCount\":0","\"totalCount\":1,\"totalCount\":0"),normal+"{}"))assertThrows(CollectionFailure.class,()->client(hub).decode(bytes(s),1));}
 @ParameterizedTest @ValueSource(booleans={false,true}) void existingEmptyFormsAndValidShortPageArePreservedWithoutCoverageApproval(boolean hub){assertEquals(1,client(hub).decode(bytes(envelope(1,"{\"item\":["+ROW+"]}",",\"numOfRows\":10")),1).items().size());for(String empty:List.of("\"\"","[]","{\"item\":[]}"))assertTrue(client(hub).decode(bytes(envelope(0,empty,",\"numOfRows\":10")),1).items().isEmpty());String xml="<response><header><resultCode>00</resultCode></header><body><pageNo>1</pageNo><numOfRows>10</numOfRows><totalCount>0</totalCount><items/></body></response>";assertTrue(client(hub).decode(bytes(xml),1).items().isEmpty());}
 @ParameterizedTest @ValueSource(booleans={false,true}) void xmlTotalsAndDeclaredSizesAreValidatedOnBothRoutes(boolean hub){for(String metadata:List.of("<numOfRows>10</numOfRows><totalCount>0</totalCount>","<numOfRows>0</numOfRows><totalCount>1</totalCount>")){String xml="<response><header><resultCode>00</resultCode></header><body><pageNo>1</pageNo>"+metadata+"<items><item><tmSeq>1</tmSeq></item></items></body></response>";assertThrows(CollectionFailure.class,()->client(hub).decode(bytes(xml),1));}}

}

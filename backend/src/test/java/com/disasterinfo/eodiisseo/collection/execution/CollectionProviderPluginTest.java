package com.disasterinfo.eodiisseo.collection.execution;

import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort.*;
import com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionProviderPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CollectionProviderPluginTest {
    static final Request REQUEST=new Request(LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1,2,Duration.ofSeconds(3));
    @ParameterizedTest @ValueSource(strings={"KMA_PORTAL_EQK_MSG","KMA_HUB_EQK_MSG","KMA_WARNING_LIST","KMA_RAIN_CODES","MOIS_MESSAGES","MOIS_SHELTERS"})
    void sixOperationsUseExplicitTransportAndExistingEnvelopeValidation(String contract){
        var row=Map.<String,Object>of("SN","1","ARCD","1","ACMDFCLTY_SN","2","tmFc","20261101120000","stnId","108","tmSeq","1","fcTp","3","areaCode","L100");
        Object body=contract.startsWith("MOIS_")?Map.of("header",Map.of("resultCode","00"),"totalCount",1,"body",List.of(row)):
            Map.of("response",Map.of("header",Map.of("resultCode","00"),"body",Map.of("totalCount",1,"items",Map.of("item",List.of(row)))));
        var calls=new AtomicInteger();var plugin=new OfficialCollectionProviderPlugin(contract,(actual,request)->{
            assertEquals(contract,actual);assertEquals(REQUEST,request);calls.incrementAndGet();
            return new Reply(200,JsonMapper.builder().build().writeValueAsBytes(body),Duration.ZERO);
        });
        var page=plugin.decode(plugin.exchange(REQUEST),REQUEST);
        assertEquals(1,page.total());assertEquals(1,page.items().size());assertFalse(plugin.identity(page.items().getFirst()).isBlank());assertEquals(1,calls.get());
        assertFalse(plugin.evidence().boundedRange());assertFalse(plugin.evidence().stableSnapshot());assertFalse(plugin.evidence().incremental());
    }
    @Test void unsupportedWildfireAndMessageEndDateNeverCallTransport(){
        var fire=new OfficialCollectionProviderPlugin("MOIS_WILDFIRE",(c,r)->{fail("No fire transport");return null;});
        assertFalse(fire.executable());assertThrows(RuntimeException.class,()->fire.exchange(REQUEST));
        var message=new OfficialCollectionProviderPlugin("MOIS_MESSAGES",(c,r)->{fail("No unsupported date range");return null;});
        assertThrows(InvalidResponse.class,()->message.exchange(new Request(REQUEST.from(),REQUEST.to().plusDays(1),1,2,Duration.ofSeconds(3))));
    }
    @Test void noAutomaticPortalHubFallbackOnFailure(){
        var hubCalls=new AtomicInteger();var portal=new OfficialCollectionProviderPlugin("KMA_PORTAL_EQK_MSG",(c,r)->{throw new IllegalStateException("synthetic failure");});
        var hub=new OfficialCollectionProviderPlugin("KMA_HUB_EQK_MSG",(c,r)->{hubCalls.incrementAndGet();return null;});
        assertThrows(IllegalStateException.class,()->portal.exchange(REQUEST));assertEquals(0,hubCalls.get());assertEquals("KMA_HUB_EQK_MSG",hub.contractKey());
    }
}
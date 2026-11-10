package com.disasterinfo.eodiisseo.collection.execution;

import com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionHttpTransport;
import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OfficialCollectionHttpTransportTest {
    final Request request=new Request(LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1,2,Duration.ofSeconds(3));
    @Test void portalAndHubHaveSeparateAuthParametersAndNoFallback(){
        var calls=new AtomicInteger();
        var transport=new OfficialCollectionHttpTransport(Map.of("KMA_PORTAL_EQK_MSG",()->"SYNTHETIC+/=", "KMA_HUB_EQK_MSG",()->"SYNTHETIC-HUB"),(uri,timeout)->{
            assertEquals(request.timeout(),timeout);assertTrue(uri.getPath().endsWith("/getEqkMsg"));
            if(uri.getHost().equals("apis.data.go.kr")){assertTrue(uri.getRawQuery().contains("serviceKey=SYNTHETIC%2B%2F%3D"));assertFalse(uri.getRawQuery().contains("authKey"));}
            else{assertEquals("apihub.kma.go.kr",uri.getHost());assertTrue(uri.getRawQuery().contains("authKey=SYNTHETIC-HUB"));assertFalse(uri.getRawQuery().contains("serviceKey"));}
            calls.incrementAndGet();return new Reply(200,new byte[0],Duration.ZERO);
        });
        transport.exchange("KMA_PORTAL_EQK_MSG",request);transport.exchange("KMA_HUB_EQK_MSG",request);assertEquals(2,calls.get());
    }
    @Test void missingHubCredentialNeverUsesPortalKey(){
        var transport=new OfficialCollectionHttpTransport(Map.of("KMA_PORTAL_EQK_MSG",()->"SYNTHETIC"),(uri,timeout)->{fail("No fallback");return null;});
        assertThrows(RuntimeException.class,()->transport.exchange("KMA_HUB_EQK_MSG",request));assertThrows(RuntimeException.class,()->transport.exchange("MOIS_WILDFIRE",request));
    }
    @Test void messageStartDateAndShelterOperationStayDistinct(){
        var transport=new OfficialCollectionHttpTransport(Map.of("MOIS_MESSAGES",()->"SYNTHETIC", "MOIS_SHELTERS",()->"SYNTHETIC"),(uri,timeout)->{
            if(uri.getPath().endsWith("DSSP-IF-00247"))assertTrue(uri.getRawQuery().contains("crtDt=20261101"));
            else{assertTrue(uri.getPath().endsWith("DSSP-IF-10943"));assertFalse(uri.getRawQuery().contains("crtDt"));}
            return new Reply(200,new byte[0],Duration.ZERO);
        });
        transport.exchange("MOIS_MESSAGES",request);transport.exchange("MOIS_SHELTERS",request);
    }
    @Test void senderExceptionsCannotExposeSignedUrlOrSecret(){
        var transport=new OfficialCollectionHttpTransport(Map.of("MOIS_MESSAGES",()->"SYNTHETIC-private"),(uri,timeout)->{throw new IllegalStateException(uri.toString());});
        var error=assertThrows(RuntimeException.class,()->transport.exchange("MOIS_MESSAGES",request));assertNull(error.getCause());assertFalse(error.getMessage().contains("SYNTHETIC-private"));assertFalse(transport.toString().contains("SYNTHETIC-private"));
    }
}
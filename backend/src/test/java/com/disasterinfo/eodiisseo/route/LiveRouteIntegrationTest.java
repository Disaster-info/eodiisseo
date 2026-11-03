package com.disasterinfo.eodiisseo.route;

import com.disasterinfo.eodiisseo.integration.tmap.*;
import com.disasterinfo.eodiisseo.route.dto.ShelterRouteRequest;
import com.disasterinfo.eodiisseo.shelter.ShelterService;
import com.disasterinfo.eodiisseo.shelter.entity.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="EODIISSEO_LIVE_TMAP_TESTS",matches="true")
class LiveRouteIntegrationTest {
    @Test void realPedestrianRoutePreservesCoordinatesAndUnits(){
        String key=System.getenv("TMAP_APP_KEY");assertNotNull(key);assertFalse(key.isBlank());
        var client=new TmapClient(new TmapProperties("https://apis.openapi.sk.com",key,Duration.ofSeconds(3),Duration.ofSeconds(5)));
        var shelters=mock(ShelterService.class);UUID id=UUID.randomUUID();
        when(shelters.getShelter(id)).thenReturn(new Shelter(id,"TEST:PUBLIC_COORDINATES","Destination",null,37.5047,127.0364,ShelterType.EARTHQUAKE_OUTDOOR,null,null,"TEST",null));
        var route=new RouteService(client,shelters).findShelterRoute(id,new ShelterRouteRequest(37.5006,127.0364));
        assertEquals("TMAP",route.provider());assertEquals(id,route.destination().shelterId());
        assertTrue(route.routePath().size()>1);assertNotNull(route.distanceMeters());assertTrue(route.distanceMeters()>0);
        assertNotNull(route.estimatedDurationSeconds());assertTrue(route.estimatedDurationSeconds()>0);
    }
}

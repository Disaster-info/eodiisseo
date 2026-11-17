package com.disasterinfo.eodiisseo.weather.client;

import com.disasterinfo.eodiisseo.integration.safetydata.EarthquakeShelterItem;
import com.disasterinfo.eodiisseo.integration.safetydata.SafetydataResponseDto;
import com.disasterinfo.eodiisseo.integration.tmap.TmapResponseDto;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalApiFixtureTest {
    final Path data=Path.of("../mock-data/scenarios");final ObjectMapper json=new ObjectMapper();
    JsonNode read(String file) throws Exception{return json.readTree(Files.readString(data.resolve(file)));}
    @Test void disasterFixturesContainOnlyKnownSourceFieldsAndNoInventedDomainState() throws Exception {
        Set<String> envelope=Set.of("disasterMessageResponse","weatherObservationResponse","weatherForecastResponse");
        Set<String> messageFields=Set.of("SN","CRT_DT","MSG_CN","RCPTN_RGN_NM","EMRG_STEP_NM");
        for(String file:List.of("earthquake.json","heavy-rain.json","wildfire.json")){
            var root=read(file);assertEquals(envelope,root.propertyNames());
            var message=root.get("disasterMessageResponse").get("body").get(0);
            assertEquals(messageFields,message.propertyNames());assertTrue(message.get("MSG_CN").asText().startsWith("[TEST]"));
            var observations=root.get("weatherObservationResponse").get("response").get("body").get("items").get("item");
            assertEquals(2,observations.size());
            for(var item:observations){assertTrue(Set.of("T1H","PTY").contains(item.get("category").asText()));assertTrue(item.has("obsrValue"));}
            var forecast=root.get("weatherForecastResponse").get("response").get("body").get("items").get("item").get(0);
            assertEquals("SKY",forecast.get("category").asText());assertTrue(forecast.has("fcstTime"));
            for(String forbidden:List.of("members","group","severity","occurredAt","endedAt","boundaryWkt","aiSummary","checklist","notification","collection"))assertFalse(root.has(forbidden));
        }
    }
    @Test void shelterFixturesDeserializeWithExistingProviderDtoAndPreserveUnknownFieldsAsNull() throws Exception {
        var type=json.getTypeFactory().constructParametricType(SafetydataResponseDto.class,EarthquakeShelterItem.class);
        SafetydataResponseDto<EarthquakeShelterItem> result=json.readValue(Files.readString(data.resolve("earthquake-shelters.json")),type);
        assertTrue(result.header().isOk());assertEquals(2,result.items().size());assertEquals(250,result.items().getFirst().capacity());
        assertNull(result.items().get(1).capacity());assertNull(result.items().get(1).tel());
        SafetydataResponseDto<EarthquakeShelterItem> empty=json.readValue(Files.readString(data.resolve("earthquake-shelters-empty.json")),type);
        assertTrue(empty.items().isEmpty());
    }
    @Test void tmapFixturesUseExistingGeometryAndTotalsContract() throws Exception {
        var route=json.readValue(Files.readString(data.resolve("tmap-pedestrian.json")),TmapResponseDto.class);
        assertEquals("FeatureCollection",route.type());assertEquals(3,route.features().size());
        assertEquals(680,route.features().getFirst().properties().totalDistance());assertEquals(540,route.features().getFirst().properties().totalTime());
        assertTrue(route.features().get(1).geometry().isLineString());assertEquals(3,route.features().get(1).geometry().line().size());
        assertTrue(json.readValue(Files.readString(data.resolve("tmap-pedestrian-empty.json")),TmapResponseDto.class).features().isEmpty());
    }
}

package com.disasterinfo.eodiisseo.disaster;
import com.disasterinfo.eodiisseo.disaster.service.*;
import com.disasterinfo.eodiisseo.disaster.collection.*;
import com.disasterinfo.eodiisseo.disaster.port.*;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class VerifiedIncidentServiceTest {
    @Test void absentContractNeverCreatesAnEarthquakeOrFire(){
        UUID source=UUID.randomUUID();var db=mock(OwnedDatabase.class);
        when(db.count(anyString(),eq(source),anyString())).thenReturn(1L);
        var earthquake=mock(DisasterService.class);var events=mock(DisasterRuntimeEvents.class);
        var service=new VerifiedIncidentService(db,earthquake,events,Optional.empty());
        var quake=Map.<String,Object>of("stnId","108","tmFc","202611011000","tmSeq","1");
        assertThat(service.normalize(source,OfficialProviderClient.Provider.EARTHQUAKE,quake,OfficialProviderClient.canonical(quake),Instant.now()).classification()).isEqualTo("EARTHQUAKE_UNLINKED");
        var fire=Map.<String,Object>of("SN","test","DST_SE_NM","산불");
        assertThat(service.normalize(source,OfficialProviderClient.Provider.WILDFIRE_MESSAGES,fire,OfficialProviderClient.canonical(fire),Instant.now()).classification()).isEqualTo("WILDFIRE_MESSAGE_UNLINKED");
        verifyNoInteractions(earthquake,events);verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void provenanceDoesNotOverrideIncorrectContentOrAnnouncement(){
        UUID source=UUID.randomUUID();var db=mock(OwnedDatabase.class);
        when(db.count(anyString(),eq(source),anyString())).thenReturn(1L);
        var row=Map.<String,Object>of("SN","test","DST_SE_NM","산불","CRT_DT","2026-11-01 10:00:00");
        String original=OfficialProviderClient.canonical(row),hash=AuthService.hash(original);
        var provider=OfficialProviderClient.Provider.WILDFIRE_MESSAGES;
        var issued=Instant.parse("2026-11-01T01:00:00Z");
        for(String badHash:List.of("wrong-hash",hash)){
            var link=new VerifiedIncidentLinkPort.Link(source,provider,badHash.equals(hash)?"OTHER_ANNOUNCEMENT":"test",badHash,"EVENT",1,issued,issued,
                VerifiedIncidentLinkPort.State.OCCURRED,null,URI.create("https://www.safetydata.go.kr/test"),"TEST");
            var service=new VerifiedIncidentService(db,mock(DisasterService.class),mock(DisasterRuntimeEvents.class),Optional.of((s,p,a,h)->Optional.of(link)));
            assertThat(service.normalize(source,provider,row,original,issued.plusSeconds(1)).classification()).isEqualTo("INVALID_INCIDENT_EVIDENCE");
        }
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}

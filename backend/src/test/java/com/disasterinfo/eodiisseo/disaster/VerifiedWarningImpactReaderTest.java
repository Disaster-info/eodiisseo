package com.disasterinfo.eodiisseo.disaster;
import com.disasterinfo.eodiisseo.disaster.adapter.VerifiedWarningImpactReader;
import com.disasterinfo.eodiisseo.disaster.port.*;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import org.junit.jupiter.api.*;
import java.time.*;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class VerifiedWarningImpactReaderTest {
    private final UUID disaster=UUID.randomUUID();
    private final Instant issued=Instant.parse("2026-11-01T01:00:00Z"),now=Instant.parse("2026-11-03T01:00:00Z");
    private OwnedDatabase db;private Map<String,OfficialWarningAreaMappingPort.Mapping> mappings;private VerifiedWarningImpactReader reader;
    @BeforeEach void setup(){
        db=mock(OwnedDatabase.class);mappings=new HashMap<>();
        when(db.rows(anyString(),eq(disaster))).thenReturn(List.of(Map.of("source_record_key","L_TEST:2026-11-01T01:00:00Z","source_updated_at",issued,"status","ONGOING")));
        when(db.one(contains("from admin_area"),eq("EXACT"))).thenReturn(Map.of("area_code","EXACT","name","SYNTHETIC","level","SIGUNGU","active",true));
        reader=new VerifiedWarningImpactReader(db,Optional.of((zone,issue,asOf)->Optional.ofNullable(mappings.get(zone))));
    }
    private OfficialWarningAreaMappingPort.Mapping mapping(String zone,Set<String> codes,Instant from,Instant until,boolean complete){
        return new OfficialWarningAreaMappingPort.Mapping(zone,codes,URI.create("https://www.weather.go.kr/test-only"),"SYNTHETIC",from,until,complete);
    }
    @Test void noProviderOrNoPublicationIsUnknown(){
        assertThat(new VerifiedWarningImpactReader(db,Optional.empty()).find(disaster,now).knowledge()).isEqualTo(DisasterImpactAreaReader.Knowledge.UNKNOWN);
        assertThat(reader.find(disaster,now).exactAdminAreaCodes()).isEmpty();
    }
    @Test void exactCompleteCurrentCrosswalkIsVerified(){
        mappings.put("L_TEST",mapping("L_TEST",Set.of("EXACT"),issued.minusSeconds(1),now.plusSeconds(1),true));
        assertThat(reader.find(disaster,now).knowledge()).isEqualTo(DisasterImpactAreaReader.Knowledge.VERIFIED);
        assertThat(reader.find(disaster,now).exactAdminAreaCodes()).containsExactly("EXACT");
    }
    @Test void partialMismatchedExpiredOrNotYetValidCrosswalkIsUnknown(){
        for(var invalid:List.of(
            mapping("LXTEST",Set.of("EXACT"),issued,now.plusSeconds(1),true),
            mapping("L_TEST",Set.of("EXACT"),issued,now,true),
            mapping("L_TEST",Set.of("EXACT"),issued,now.plusSeconds(1),false),
            mapping("L_TEST",Set.of("EXACT"),issued.plusSeconds(1),now.plusSeconds(1),true),
            mapping("L_TEST",Set.of(),issued,now.plusSeconds(1),true))){
            mappings.put("L_TEST",invalid);assertThat(reader.find(disaster,now).knowledge()).isEqualTo(DisasterImpactAreaReader.Knowledge.UNKNOWN);
        }
    }
    @Test void unknownAdminCodeOrSimilarNameCannotEstablishMapping(){
        mappings.put("L_TEST",mapping("L_TEST",Set.of("EXACT_SUFFIX"),issued,now.plusSeconds(1),true));
        assertThat(reader.find(disaster,now).knowledge()).isEqualTo(DisasterImpactAreaReader.Knowledge.UNKNOWN);
        verify(db,never()).rows(contains("interest_area"),any());
    }
    @Test void competingProviderRecordsAndNonOfficialEvidenceAreUnknown(){
        when(db.rows(anyString(),eq(disaster))).thenReturn(List.of(Map.of("source_record_key","L_TEST:x"),Map.of("source_record_key","L_TEST:y")));
        assertThat(reader.find(disaster,now).knowledge()).isEqualTo(DisasterImpactAreaReader.Knowledge.UNKNOWN);
        when(db.rows(anyString(),eq(disaster))).thenReturn(List.of(Map.of("source_record_key","L_TEST:x","source_updated_at",issued,"status","ONGOING")));
        mappings.put("L_TEST",new OfficialWarningAreaMappingPort.Mapping("L_TEST",Set.of("EXACT"),URI.create("https://www.weather.go.kr.evil.test/test"),"TEST",issued,now.plusSeconds(1),true));
        assertThat(reader.find(disaster,now).knowledge()).isEqualTo(DisasterImpactAreaReader.Knowledge.UNKNOWN);
    }
    @Test void unavailableCrosswalkNeverBecomesVerifiedOrUnrelated(){
        var failing=new VerifiedWarningImpactReader(db,Optional.of((zone,issued,asOf)->{throw new IllegalStateException("TEST FAILURE");}));
        assertThat(failing.find(disaster,now).knowledge()).isEqualTo(DisasterImpactAreaReader.Knowledge.UNKNOWN);
    }}

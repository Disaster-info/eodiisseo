package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.disaster.service.*;
import com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.disasterinfo.eodiisseo.disaster.collection.OfficialWildfireInfoInspector.*;

/** Entirely synthetic. No incident, timestamp, snapshot, or provider approval is fabricated. */
class OfficialWildfireInfoInspectorTest {
    Map<String,Object> row(){
        var row=new LinkedHashMap<String,Object>();row.put("FRSTFR_INFO_ID","SYNTHETIC-0001");
        row.put("FRSTFR_GNT_DT","2026/11/01 10:00:00");row.put("FRST_REG_DT","2026/11/01 11:00:00.123456789");
        row.put("FRST_MDFCN_DT","2026/11/01 12:00:00.123456789");return row;
    }
    void unlinked(Inspection result){
        assertThat(result.decision()).isEqualTo(Decision.UNLINKED);
        assertThat(result.gaps()).containsExactlyInAnyOrder(Gap.values());
    }
    @Test void namesStaffIdsAddressesNarrativesAndUnknownFieldsNeverEnterReviewOutput(){
        var row=row();var original=new LinkedHashMap<>(row);
        Object forbidden=new Object(){@Override public String toString(){throw new AssertionError("Sensitive value must not be rendered");}};
        for(String field:List.of("FRSTFR_DCL_NM","GRNDS_0_RBPRSN_NM","FTRXTNGSH_RBPRSN_NM","FRST_RGTR_ID","FRST_MDFR_ID",
            "FRSTFR_DCLR_ADDR","FRSTFR_GNT_PLC","FRSTFR_OCRN_HONU_NM","FRSTFR_OCRN_CS_DTL_CN","FTRXTNGSH_ACTN_MTTR",
            "FRSTFR_GRNDS_OPER_RSLT","FRSTFR_GRNDS_ETC_MTTR","FRSTFR_PSTN_XCRD","FRSTFR_PSTN_YCRD","UNKNOWN_PERSONAL_FIELD"))row.put(field,forbidden);
        row.put("LINK_MSG_ID",forbidden);row.put("LINK_TRNS_ID",forbidden);
        var result=inspect(DATASET,row);unlinked(result);
        assertThat(result.reviewFields()).isEqualTo(original);
        assertThat(result.transportMetadataPresent()).containsExactlyInAnyOrder("LINK_MSG_ID","LINK_TRNS_ID");
        assertThat(result.toString()).doesNotContain("SYNTHETIC-0001","2026/11","LINK_MSG_ID");
        assertThat(result.issues()).isEmpty();
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","\t"})
    void missingOrBlankCandidateIdNeverFallsBackToMessageOrTransactionId(String id){
        var row=row();row.put("FRSTFR_INFO_ID",id);row.put("SN","SYNTHETIC-0001");row.put("LINK_MSG_ID","SYNTHETIC-0001");row.put("LINK_TRNS_ID","SYNTHETIC-0001");
        var result=inspect(DATASET,row);unlinked(result);
        assertThat(result.issues()).contains(new Issue("FRSTFR_INFO_ID",IssueCode.CANDIDATE_ID_REQUIRED));
        assertThat(result.reviewFields()).doesNotContainKeys("SN","LINK_MSG_ID","LINK_TRNS_ID");
    }
    @Test void absentRecordAndAbsentIdHaveExplicitSafeIssues(){
        var result=inspect(DATASET,null);unlinked(result);
        assertThat(result.reviewFields()).isEmpty();assertThat(result.issues()).containsExactly(new Issue("RECORD",IssueCode.MISSING_RECORD));
        result=inspect(DATASET,Map.of("LINK_MSG_ID","SN-ONLY"));unlinked(result);
        assertThat(result.issues()).containsExactly(new Issue("FRSTFR_INFO_ID",IssueCode.CANDIDATE_ID_REQUIRED));
    }
    @Test void longIdsAreNotTruncatedToFitExistingIncidentKeyLimit(){
        var row=row();String candidate="A".repeat(1000);row.put("FRSTFR_INFO_ID",candidate);
        var result=inspect(DATASET,row);unlinked(result);assertThat(result.reviewFields().get("FRSTFR_INFO_ID")).isEqualTo(candidate);
        row.put("FRSTFR_INFO_ID",candidate+"A");result=inspect(DATASET,row);unlinked(result);
        assertThat(result.reviewFields()).doesNotContainKey("FRSTFR_INFO_ID");
        assertThat(result.issues()).contains(new Issue("FRSTFR_INFO_ID",IssueCode.UNSUPPORTED_TEXT));
    }
    @ParameterizedTest @ValueSource(strings={"A\nB","A\rB","A\u0000B","A\u007fB"})
    void controlCharactersAreNotRetainedInStructuredReviewFields(String id){
        var row=row();row.put("FRSTFR_INFO_ID",id);var result=inspect(DATASET,row);unlinked(result);
        assertThat(result.reviewFields()).doesNotContainKey("FRSTFR_INFO_ID");
        assertThat(result.issues()).contains(new Issue("FRSTFR_INFO_ID",IssueCode.UNSUPPORTED_TEXT));
        assertThat(result.toString()).doesNotContain(id);
    }
    @Test void unsupportedWireTypesAreNotCoercedAndDoNotLeakValues(){
        for(Object value:List.of(12345L,Map.of("secret","SYNTHETIC PRIVATE"),List.of("SYNTHETIC PRIVATE"),
            new Object(){@Override public String toString(){throw new AssertionError("No coercion allowed");}})){
            var row=row();row.put("FRST_MDFCN_DT",value);var result=inspect(DATASET,row);unlinked(result);
            assertThat(result.reviewFields()).doesNotContainKey("FRST_MDFCN_DT");
            assertThat(result.issues()).contains(new Issue("FRST_MDFCN_DT",IssueCode.UNSUPPORTED_TEXT));
            assertThat(result.toString()).doesNotContain("SYNTHETIC PRIVATE");
        }
    }
    @ParameterizedTest @ValueSource(strings={"2026/11/01 10:00:00","2026-11-01 10:00:00","2026/11/01 10:00:00.123456789", "2026-11-01T10:00:00+09:00","2026/02/30 10:00:00","2026/11/01 24:00:00","0",""})
    void timeTextIncludingInvalidDatesNeverBecomesAnInstantOrEndedState(String text){
        var row=row();for(String key:List.of("FRSTFR_GNT_DT","EXTNGS_CMPTN_DT","EXTNGS_END_DT","FTRXTNGSH_SRNC_END_DT","FRST_REG_DT","FRST_MDFCN_DT"))row.put(key,text);
        var result=inspect(DATASET,row);unlinked(result);
        for(String key:List.of("FRSTFR_GNT_DT","EXTNGS_CMPTN_DT","EXTNGS_END_DT","FTRXTNGSH_SRNC_END_DT","FRST_REG_DT","FRST_MDFCN_DT"))
            assertThat(result.reviewFields().get(key)).isEqualTo(text);
    }
    @ParameterizedTest @ValueSource(strings={"0","1","2","3","D","C","X",""})
    void transportStatusIsNotAnIncidentStateOrCancellationRule(String status){
        var row=row();row.put("LINK_STTS",status);row.put("cancel","1");row.put("officialSequence",999);
        var result=inspect(DATASET,row);unlinked(result);
        assertThat(result.reviewFields()).containsEntry("LINK_STTS",status).doesNotContainKeys("cancel","officialSequence");
    }
    @Test void repeatCorrectionReverseOrderAndConflictingTimeNeverAuthorizeAnIncident(){
        var row=row();var first=inspect(DATASET,row);var duplicate=inspect(DATASET,row);
        assertThat(duplicate).isEqualTo(first);unlinked(first);
        row.put("FRST_MDFCN_DT","2026/11/01 09:00:00.000000001");var older=inspect(DATASET,row);unlinked(older);
        row.put("EXTNGS_END_DT","2026/11/01 08:00:00");var conflict=inspect(DATASET,row);unlinked(conflict);
        assertThat(conflict.reviewFields()).containsEntry("EXTNGS_END_DT","2026/11/01 08:00:00");
        assertThat(first.reviewFields()).doesNotContainKey("EXTNGS_END_DT");
    }
    @Test void legalCodeFragmentsRemainSeparateAndAreNotImpactAreas(){
        var row=row();row.put("STDG_CTPV_CD","00");row.put("STDG_SGG_CD","001");row.put("STDG_EMD_CD","002");row.put("STDG_STLI_CD","03");
        var result=inspect(DATASET,row);unlinked(result);
        assertThat(result.reviewFields()).containsEntry("STDG_CTPV_CD","00").containsEntry("STDG_SGG_CD","001")
            .containsEntry("STDG_EMD_CD","002").containsEntry("STDG_STLI_CD","03").doesNotContainKeys("areaCode","IMPACT");
        assertThatThrownBy(()->result.reviewFields().put("areaCode","GUESS")).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"DSSP-IF-00247","DSSP-IF-10346?serviceKey=SYNTHETIC_PRIVATE","UNKNOWN"})
    void datasetMustBeExplicitAndNeverGuessedFromMatchingMessageIds(String dataset){
        var row=row();row.put("SN",row.get("FRSTFR_INFO_ID"));row.put("LINK_MSG_ID",row.get("FRSTFR_INFO_ID"));
        var result=inspect(dataset,row);unlinked(result);assertThat(result.reviewFields()).isEmpty();
        assertThat(result.transportMetadataPresent()).isEmpty();assertThat(result.issues()).containsExactly(new Issue("DATASET",IssueCode.DATASET_MISMATCH));
        assertThat(result.toString()).doesNotContain("SYNTHETIC_PRIVATE");
    }
    @Test void forestInfoIdAndLinkMessageIdNeverSupplyAnAnnouncementToTheExistingMessageResolver(){
        var row=row();row.put("LINK_MSG_ID","SYNTHETIC-SN");row.put("LINK_TRNS_ID","SYNTHETIC-SN");row.put("DST_SE_NM","산불");
        var db=mock(OwnedDatabase.class);UUID source=UUID.randomUUID();
        when(db.count(anyString(),eq(source),anyString())).thenReturn(1L);
        var earthquakes=mock(DisasterService.class);var events=mock(DisasterRuntimeEvents.class);var links=mock(VerifiedIncidentLinkPort.class);
        var service=new VerifiedIncidentService(db,earthquakes,events,Optional.of(links));
        var result=service.normalize(source,OfficialProviderClient.Provider.WILDFIRE_MESSAGES,row,
            OfficialProviderClient.canonical(row),Instant.parse("2026-11-02T00:00:00Z"));
        assertThat(result.classification()).isEqualTo("INVALID_FACTS");assertThat(result.sourceRecord()).isNull();
        verifyNoInteractions(links,earthquakes,events);verify(db,never()).update(anyString(),any(Object[].class));
    }
}
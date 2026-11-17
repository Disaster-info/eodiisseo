package com.disasterinfo.eodiisseo.disaster.collection;

import java.util.*;

/**
 * Offline, privacy-minimized review of DSSP-IF-10346 documented field names only.
 * Not a provider client, Spring bean, incident resolver, timestamp parser, or persistence adapter.
 * Field presence never establishes event identity, lifecycle, revision order, or a message association.
 */
public final class OfficialWildfireInfoInspector {
    public static final String DATASET="DSSP-IF-10346";
    private OfficialWildfireInfoInspector() {}
    public enum Decision { UNLINKED }
    public enum Gap {
        SOURCE_PROVENANCE, IDENTITY_SCOPE, TIME_FORMAT_AND_ZONE, LIFECYCLE_CODES,
        REVISION_ORDER_AND_WITHDRAWAL, MESSAGE_ASSOCIATION, IMPACT_MAPPING,
        PAGINATION_AND_INCREMENTAL_REPLAY, DATASET_APPROVAL_AND_RIGHTS
    }
    public enum IssueCode { DATASET_MISMATCH, MISSING_RECORD, CANDIDATE_ID_REQUIRED, UNSUPPORTED_TEXT }
    public record Issue(String field,IssueCode code) {}
    public record Inspection(Decision decision,Map<String,String> reviewFields,
            Set<String> transportMetadataPresent,List<Issue> issues,Set<Gap> gaps) {
        public Inspection {
            reviewFields=Map.copyOf(reviewFields);transportMetadataPresent=Set.copyOf(transportMetadataPresent);
            issues=List.copyOf(issues);gaps=Set.copyOf(gaps);
        }
        @Override public String toString(){
            return "WildfireInfoInspection[decision="+decision+", reviewFieldCount="+reviewFields.size()+", issueCount="+issues.size()+"]";
        }
    }
    // Local bounded text support shaped by the public column sizes, not a claim about wire types.
    // No names, staff IDs, precise addresses, coordinates, or free-text narrative fields are retained.
    private static final Map<String,Integer> REVIEW_FIELDS=Map.ofEntries(
        Map.entry("FRSTFR_INFO_ID",1000),Map.entry("FRSTFR_GNT_DT",1000),Map.entry("FRSTFR_DCLR_YMD",1000),
        Map.entry("EXTNGS_CMPTN_DT",1000),Map.entry("EXTNGS_END_DT",50),Map.entry("FTRXTNGSH_SRNC_END_DT",50),
        Map.entry("FRST_REG_DT",50),Map.entry("FRST_MDFCN_DT",50),Map.entry("MAAS_OBTN_YMD",50),
        Map.entry("LINK_STTS",1),Map.entry("NDMS_LINK_PRCS_HR",30),
        Map.entry("STDG_CTPV_CD",50),Map.entry("STDG_SGG_CD",3),Map.entry("STDG_EMD_CD",3),Map.entry("STDG_STLI_CD",2));
    private static final Set<String> TRANSPORT_FIELDS=Set.of("LINK_MSG_ID","LINK_TRNS_ID");
    public static Inspection inspect(String dataset,Map<String,?> row){
        var fields=new LinkedHashMap<String,String>();var present=new HashSet<String>();var issues=new ArrayList<Issue>();
        if(!DATASET.equals(dataset)){
            issues.add(new Issue("DATASET",IssueCode.DATASET_MISMATCH));
        }else if(row==null){
            issues.add(new Issue("RECORD",IssueCode.MISSING_RECORD));
        }else{
            for(var spec:REVIEW_FIELDS.entrySet()){
                if(!row.containsKey(spec.getKey()))continue;
                Object value=row.get(spec.getKey());
                if(!(value instanceof String text) || text.length()>spec.getValue() || text.chars().anyMatch(Character::isISOControl)){
                    issues.add(new Issue(spec.getKey(),IssueCode.UNSUPPORTED_TEXT));continue;
                }
                fields.put(spec.getKey(),text); // Exact text only: no trim, timezone, code concatenation, or chronology inference.
            }
            // Values are deliberately not accessed or retained; these identifiers are not message SN evidence.
            for(String name:TRANSPORT_FIELDS)if(row.containsKey(name))present.add(name);
            if(!fields.containsKey("FRSTFR_INFO_ID")||fields.get("FRSTFR_INFO_ID").isBlank())
                issues.add(new Issue("FRSTFR_INFO_ID",IssueCode.CANDIDATE_ID_REQUIRED));
        }
        return new Inspection(Decision.UNLINKED,fields,present,issues,EnumSet.allOf(Gap.class));
    }
}
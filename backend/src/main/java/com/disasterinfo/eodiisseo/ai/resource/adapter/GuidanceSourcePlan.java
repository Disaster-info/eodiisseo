package com.disasterinfo.eodiisseo.ai.resource.adapter;

import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Metadata-only planning for sources whose reuse permission/content inspection is pending.
 * Reuses the import tool's source boundary. Never fetches, imports, reviews, or approves a resource.
 * Plan fingerprints are NOT official-content fingerprints or approval evidence.
 */
public final class GuidanceSourcePlan {
    private static final Set<String> HAZARDS=Set.of("EARTHQUAKE","HEAVY_RAIN","WILDFIRE");
    private static final Set<String> SECTIONS=Set.of("PREPARE","DURING","EVACUATE","AFTER","ASSISTANCE","DONT");
    private GuidanceSourcePlan(){}
    public static Map<String,Object> inspect(Map<String,Object> input){
        if(input==null||input.size()>18)throw new IllegalArgumentException("Bounded source metadata required");
        String url=text(input,"sourceUrl",2000);
        OfficialGuidanceImporter.officialUri(url);
        String publisher=text(input,"publisher",100),hazard=text(input,"disasterType",30);
        if(!"행정안전부".equals(publisher)||!HAZARDS.contains(hazard))throw new IllegalArgumentException("Unsupported official source");
        if(input.get("contentFingerprint")!=null||!Set.of("PENDING","METADATA_ONLY").contains(text(input,"approvalState",30))
            ||!Set.of("UNCONFIRMED","PERMISSION_REQUIRED").contains(text(input,"rightsState",30)))
            throw new IllegalArgumentException("A source plan cannot carry content approval");
        Instant checked=Instant.parse(text(input,"checkedAt",40));
        if(checked.isAfter(Instant.now()))throw new IllegalArgumentException("Future source verification");
        LocalDate registered=LocalDate.parse(text(input,"registeredDate",10));
        if(registered.isAfter(checked.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()))throw new IllegalArgumentException("Invalid registration date");
        Object value=input.get("sections");
        if(!(value instanceof List<?> values)||values.isEmpty()||values.size()>6)throw new IllegalArgumentException("Bounded section plan required");
        List<Map<String,Object>> sections=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object entry:values){
            if(!(entry instanceof Map<?,?> raw))throw new IllegalArgumentException("Invalid section metadata");
            var section=(Map<String,Object>)raw;
            String code=text(section,"section",20),locator=text(section,"sourceLocator",200);
            if(!SECTIONS.contains(code)||!seen.add(code))throw new IllegalArgumentException("Invalid section scope");
            var planned=new TreeMap<String,Object>();planned.put("section",code);planned.put("sourceLocator",locator);planned.put("state","PENDING_HUMAN_INSPECTION");
            sections.add(Collections.unmodifiableMap(planned));
        }
        var result=new TreeMap<String,Object>();
        result.put("sourceUrl",url);result.put("sourceTitle",text(input,"sourceTitle",255));
        result.put("publisher",publisher);result.put("disasterType",hazard);
        result.put("registeredDate",registered.toString());result.put("checkedAt",checked.toString());
        result.put("sourceVersion",text(input,"sourceVersion",100));result.put("mediaType",text(input,"mediaType",100));
        String rightsUrl=text(input,"rightsEvidenceUrl",2000);OfficialGuidanceImporter.officialUri(rightsUrl);
        result.put("rightsEvidenceUrl",rightsUrl);result.put("rightsState",input.get("rightsState"));
        result.put("sections",List.copyOf(sections));result.put("approvalState","PENDING");result.put("contentFingerprint",null);
        result.put("runtimeSearchEligible",false);
        byte[] metadata=JsonMapper.builder().build().writeValueAsBytes(result);
        try{result.put("planFingerprint",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(metadata)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
        return Collections.unmodifiableMap(result);
    }
    private static String text(Map<String,Object> input,String key,int limit){
        Object value=input.get(key);
        if(!(value instanceof String text)||text.isBlank()||text.length()>limit)throw new IllegalArgumentException("Invalid source metadata");
        return text;
    }
}

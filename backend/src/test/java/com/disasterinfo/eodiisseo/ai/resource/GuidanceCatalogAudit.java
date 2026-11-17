package com.disasterinfo.eodiisseo.ai.resource;

import com.disasterinfo.eodiisseo.ai.resource.adapter.GuidanceSourcePlan;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

/** Explicit private-file audit utility. Never discovers, publishes, imports or approves a catalog. */
public final class GuidanceCatalogAudit {
    private GuidanceCatalogAudit() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Supply one explicitly reviewed private catalog path");
        validate(Files.readString(Path.of(args[0])));
        System.out.println("CATALOG_AUDIT_OK metadataCandidates=7 imported=0 approved=0");
    }
    public static void validate(String json) {
        Map root=JsonMapper.builder().build().readValue(json,Map.class);
        Object value=root.get("sources");
        if(!(value instanceof List<?> sources)||sources.size()!=7)throw new IllegalArgumentException("Seven candidate plans required");
        for(var raw:sources){
            if(!(raw instanceof Map<?,?>))throw new IllegalArgumentException("Candidate metadata required");
            var plan=GuidanceSourcePlan.inspect((Map<String,Object>)raw);
            if(!Boolean.FALSE.equals(plan.get("runtimeSearchEligible"))||!"PENDING".equals(plan.get("approvalState"))
                ||plan.get("contentFingerprint")!=null||plan.containsKey("contentText")||plan.containsKey("html")||plan.containsKey("resourceId"))
                throw new IllegalArgumentException("Unimported and unapproved metadata-only plans required");
        }
    }
}
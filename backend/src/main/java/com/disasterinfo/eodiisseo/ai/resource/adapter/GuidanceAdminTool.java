package com.disasterinfo.eodiisseo.ai.resource.adapter;

import com.disasterinfo.eodiisseo.ai.resource.port.OfficialResourceStore;
import com.disasterinfo.eodiisseo.auth.service.*;
import com.disasterinfo.eodiisseo.auth.security.*;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.time.Instant;
import java.util.*;

/**
 * Explicit internal stdin/stdout management tool. No Controller, runner, automatic import or network access.
 * Call run(bearer, reader, writer) from an authorised development console with an ACTIVE OPS token.
 * One bounded JSON command per invocation. Output contains metadata only, never token/raw HTML.
 */
@Service @Profile("!test")
public class GuidanceAdminTool {
    private final TokenService tokens;private final SessionAccessService sessions;private final AuthService auth;
    private final OfficialGuidanceImporter importer;private final OfficialResourceStore store;
    private final DatabaseResourceApproval approvals;private final OwnedDatabase db;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public GuidanceAdminTool(TokenService tokens,SessionAccessService sessions,AuthService auth,OfficialGuidanceImporter importer,
        OfficialResourceStore store,DatabaseResourceApproval approvals,OwnedDatabase db){
        this.tokens=tokens;this.sessions=sessions;this.auth=auth;this.importer=importer;this.store=store;this.approvals=approvals;this.db=db;
    }
    public int run(String bearer,Reader input,Writer output)throws IOException{
        try{
            MemberPrincipal actor=tokens.verify(bearer);
            sessions.requireActiveSession(actor.memberId(),actor.sessionId());
            if(!((List<?>)auth.me(actor).get("roles")).contains("OPS"))throw ApiProblem.forbidden();
            StringBuilder command=new StringBuilder();char[] buffer=new char[4096];int count;
            while((count=input.read(buffer))!=-1){if(command.length()+count>2_097_152)throw new IllegalArgumentException();command.append(buffer,0,count);}
            Map<String,Object> request=JSON.readValue(command.toString(),Map.class);
            if(request==null)throw new IllegalArgumentException();
            String action=string(request,"operation");
            Object result;
            switch(action){
                case "PLAN" -> result=GuidanceSourcePlan.inspect(request);
                case "IMPORT" -> {
                    if(!Set.of("EARTHQUAKE","HEAVY_RAIN","WILDFIRE").contains(string(request,"hazard")))throw new IllegalArgumentException();
                    var document=importer.publish(actor,string(request,"sourceUrl"),string(request,"title"),string(request,"bodyElementId"),
                        string(request,"html").getBytes(java.nio.charset.StandardCharsets.UTF_8),Instant.parse(string(request,"expiresAt")));
                    result=inspect(document.getResourceId());
                }
                case "INSPECT" -> result=inspect(UUID.fromString(string(request,"resourceId")));
                case "APPROVE" -> {
                    UUID resource=UUID.fromString(string(request,"resourceId"));
                    approvals.review(actor,resource,string(request,"expectedFingerprint"),Instant.parse(string(request,"validUntil")),
                        string(request,"evidenceReference"),Boolean.TRUE.equals(request.get("rightsAndExternalAiUseConfirmed")),Boolean.TRUE.equals(request.get("injectionReviewed")));
                    result=inspect(resource);
                }
                case "REVOKE" -> {UUID resource=UUID.fromString(string(request,"resourceId"));approvals.revoke(actor,resource);result=inspect(resource);}
                case "EXPIRE" -> result=Map.of("erasedAcquisitions",importer.purgeExpired(actor));
                default -> throw new IllegalArgumentException();
            }
            output.write(JSON.writeValueAsString(Map.of("status","OK","result",result)));output.flush();return 0;
        }catch(RuntimeException invalid){
            output.write("{\"status\":\"REJECTED\"}");output.flush();return 1;
        }
    }
    private Map<String,Object> inspect(UUID id){
        var doc=store.findById(id).orElseThrow(IllegalArgumentException::new);
        var result=new LinkedHashMap<String,Object>();
        result.put("resourceId",id);result.put("sourceUrl",doc.getSourceUrl());result.put("agency",doc.getAgencyName());
        result.put("title",doc.getTitle());result.put("version",doc.getVersionLabel());result.put("fingerprint",doc.revisionFingerprint());
        result.put("checkedAt",doc.getCheckedAt().toString());result.put("approved",approvals.findApproval(id).isPresent());
        var metadata=db.one("select expires_at,superseded_at from owned_resource_import where resource_id=?",id);
        if(metadata!=null){
            result.put("expiresAt",OwnedDatabase.instant(metadata.get("expires_at")).toString());
            result.put("superseded",metadata.get("superseded_at")!=null);
        }
        return result;
    }
    private static String string(Map<String,Object> request,String key){
        Object value=request.get(key);if(!(value instanceof String text)||text.isBlank())throw new IllegalArgumentException();
        return text;
    }
}

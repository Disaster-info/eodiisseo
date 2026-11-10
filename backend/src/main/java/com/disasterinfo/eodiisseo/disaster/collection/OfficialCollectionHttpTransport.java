package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/** Fixed GET contracts with exact, decoded per-operation credentials. No default client, key lookup or fallback. */
public final class OfficialCollectionHttpTransport implements OfficialCollectionProviderPlugin.Transport {
    @FunctionalInterface public interface Get { Reply get(URI uri,Duration timeout); }
    private record Endpoint(String url,String keyParameter,boolean safety) {}
    private static final Map<String,Endpoint> ENDPOINTS=Map.of(
        "KMA_PORTAL_EQK_MSG",new Endpoint("https://apis.data.go.kr/1360000/EqkInfoService/getEqkMsg","serviceKey",false),
        "KMA_HUB_EQK_MSG",new Endpoint("https://apihub.kma.go.kr/api/typ02/openApi/EqkInfoService/getEqkMsg","authKey",false),
        "KMA_WARNING_LIST",new Endpoint("https://apis.data.go.kr/1360000/WthrWrnInfoService/getWthrWrnList","serviceKey",false),
        "KMA_RAIN_CODES",new Endpoint("https://apis.data.go.kr/1360000/WthrWrnInfoService/getPwnCd","serviceKey",false),
        "MOIS_MESSAGES",new Endpoint("https://www.safetydata.go.kr/V2/api/DSSP-IF-00247","serviceKey",true),
        "MOIS_SHELTERS",new Endpoint("https://www.safetydata.go.kr/V2/api/DSSP-IF-10943","serviceKey",true));
    private final Map<String,Supplier<String>> decodedCredentials;private final Get get;
    public OfficialCollectionHttpTransport(Map<String,Supplier<String>> decodedCredentials,Get get){
        this.decodedCredentials=Map.copyOf(decodedCredentials);this.get=Objects.requireNonNull(get);
        if(!ENDPOINTS.keySet().containsAll(decodedCredentials.keySet()))throw new IllegalArgumentException("Unapproved operation credentials");
    }
    public Reply exchange(String contract,Request request){
        var endpoint=ENDPOINTS.get(contract);var credential=decodedCredentials.get(contract);
        if(endpoint==null||credential==null)throw new CollectionFailure("CONTRACT_OR_CREDENTIAL_REQUIRED");
        if(contract.equals("MOIS_MESSAGES")&&!request.from().equals(request.to()))throw new CollectionFailure("UNSUPPORTED_END_DATE");
        String decoded;
        try{decoded=credential.get();}catch(RuntimeException failed){throw new CollectionFailure("KEY_REQUIRED");}
        if(decoded==null||decoded.isBlank()||decoded.length()>1024)throw new CollectionFailure("KEY_REQUIRED");
        var query=new LinkedHashMap<String,String>();query.put(endpoint.keyParameter(),decoded);
        query.put("pageNo",Integer.toString(request.page()));query.put("numOfRows",Integer.toString(request.rows()));
        query.put(endpoint.safety()?"returnType":"dataType",endpoint.safety()?"json":"JSON");
        if(contract.equals("MOIS_MESSAGES"))query.put("crtDt",request.from().format(DateTimeFormatter.BASIC_ISO_DATE));
        else if(!endpoint.safety()){
            query.put("fromTmFc",request.from().format(DateTimeFormatter.BASIC_ISO_DATE));query.put("toTmFc",request.to().format(DateTimeFormatter.BASIC_ISO_DATE));
            if(contract.equals("KMA_RAIN_CODES")){query.put("warningType","2");query.put("stnId","108");}
        }
        String parameters=query.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
        // The injected sender must disable transparent retry/redirect/proxy/logging and report Retry-After honestly.
        // Existing OfficialHttp provides bounded GET/body timeouts; no sender is constructed automatically.
        try{return get.get(URI.create(endpoint.url()+"?"+parameters),request.timeout());}
        catch(RuntimeException failed){throw new CollectionFailure("TRANSPORT");}
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    @Override public String toString(){return "OfficialCollectionHttpTransport[credentials=redacted]";}
}
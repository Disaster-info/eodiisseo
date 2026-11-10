package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.collection.execution.CollectionProviderPort;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionContractRegistry;
import java.time.*;
import java.util.*;

/** Explicit operation adapter. Reuses envelope decoders only; never calls normalization or links incidents. */
public final class OfficialCollectionProviderPlugin implements CollectionProviderPort {
    @FunctionalInterface public interface Transport { Reply exchange(String contract,Request request); }
    private final String contract;private final Transport transport;private final Evidence evidence;
    private final OfficialProviderClient decoder;
    public OfficialCollectionProviderPlugin(String contract,Transport transport){this(contract,transport,Evidence.unverified());}
    /** Reviewed evidence is injected explicitly; synthetic test evidence is not an official guarantee. */
    public OfficialCollectionProviderPlugin(String contract,Transport transport,Evidence evidence){
        if(new CollectionContractRegistry().contracts().stream().noneMatch(c->c.key().equals(contract)))throw new IllegalArgumentException("Unknown operation");
        this.contract=contract;this.transport=Objects.requireNonNull(transport);this.evidence=Objects.requireNonNull(evidence);
        OfficialProviderClient.Transport noNetwork=(uri,timeout)->{throw new IllegalStateException("Decoder never sends requests");};
        this.decoder=switch(contract){
            case "KMA_PORTAL_EQK_MSG"->new OfficialProviderClient(OfficialProviderClient.Provider.EARTHQUAKE,noNetwork);
            case "KMA_HUB_EQK_MSG"->OfficialProviderClient.earthquakeHub(noNetwork);
            case "KMA_WARNING_LIST","KMA_RAIN_CODES"->new OfficialProviderClient(OfficialProviderClient.Provider.HEAVY_RAIN,noNetwork);
            case "MOIS_MESSAGES","MOIS_SHELTERS"->new OfficialProviderClient(OfficialProviderClient.Provider.WILDFIRE_MESSAGES,noNetwork);
            default->null;
        };
    }
    public String contractKey(){return contract;}
    public Evidence evidence(){return evidence;}
    public boolean executable(){return !contract.equals("MOIS_WILDFIRE");}
    public void validateRequest(Request request){
        if(contract.equals("MOIS_MESSAGES")&&!request.from().equals(request.to()))throw new InvalidResponse("UNSUPPORTED_END_DATE");
    }
    public Reply exchange(Request request){
        if(!executable())throw new CollectionFailure("CONTRACT_REQUIRED");
        validateRequest(request);
        return transport.exchange(contract,request); // No credential or endpoint fallback.
    }
    public Page decode(Reply reply,Request request){
        try{return decodeOfficial(reply,request);}
        catch(CollectionFailure failure){throw new InvalidResponse(failure.code());}
        catch(RuntimeException invalid){throw new InvalidResponse("SCHEMA");}
    }
    private Page decodeOfficial(Reply reply,Request request){
        if(!executable())throw new CollectionFailure("CONTRACT_REQUIRED");
        var page=decoder.decode(reply.body(),request.page());
        if(page.items().size()>request.rows())throw new CollectionFailure("ROW_LIMIT");
        for(var item:page.items()){
            identity(item);
            if(contract.startsWith("KMA_")){
                var issued=LocalDate.ofInstant(OfficialRecordNormalizer.time(item.get("tmFc")),ZoneId.of("Asia/Seoul"));
                if(issued.isBefore(request.from())||issued.isAfter(request.to()))throw new CollectionFailure("WINDOW_RESPONSE_MISMATCH");
            }
        }
        return new Page(page.number(),page.total(),page.items());
    }
    public String identity(Map<String,Object> item){
        return switch(contract){
            case "MOIS_MESSAGES"->OfficialRecordNormalizer.identity(OfficialProviderClient.Provider.WILDFIRE_MESSAGES,item);
            case "MOIS_SHELTERS"->required(item,"ARCD")+":"+required(item,"ACMDFCLTY_SN");
            case "KMA_RAIN_CODES"->OfficialRecordNormalizer.identity(OfficialProviderClient.Provider.HEAVY_RAIN,item);
            case "KMA_WARNING_LIST"->OfficialRecordNormalizer.identity(OfficialProviderClient.Provider.EARTHQUAKE,item);
            case "KMA_PORTAL_EQK_MSG","KMA_HUB_EQK_MSG"->OfficialRecordNormalizer.identity(OfficialProviderClient.Provider.EARTHQUAKE,item)+":"+required(item,"fcTp");
            default->throw new CollectionFailure("CONTRACT_REQUIRED");
        };
    }
    private static String required(Map<String,Object> row,String key){
        String value=Objects.toString(row.get(key),"");if(value.isBlank()||value.length()>100)throw new CollectionFailure("SCHEMA");return value;
    }
}
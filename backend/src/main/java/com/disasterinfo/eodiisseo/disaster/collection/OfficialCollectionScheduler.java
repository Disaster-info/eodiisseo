package com.disasterinfo.eodiisseo.disaster.collection;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.*;

/** All gates default off. Explicit ownership attestation is required; existing owner service is never intercepted. */
@Component @Profile("!test")
@ConditionalOnProperty(name="eodiisseo.disaster.collection.enabled",havingValue="true")
public class OfficialCollectionScheduler implements AutoCloseable {
    private final OfficialCollectionService service;private final OwnedDatabase db;private final Environment env;private final Clock clock;
    private final OfficialHttp http=new OfficialHttp();
    private final Map<OfficialProviderClient.Provider,String> statuses=new java.util.concurrent.ConcurrentHashMap<>();
    public Map<OfficialProviderClient.Provider,String> statusSnapshot(){return Map.copyOf(statuses);}
    public OfficialCollectionScheduler(OfficialCollectionService service,OwnedDatabase db,Environment env,Clock clock){
        this.service=service;this.db=db;this.env=env;this.clock=clock;
        if(!"owned-disaster-development".equals(env.getProperty("eodiisseo.disaster.collection.owner")))
            throw new CollectionFailure("OWNER_CONFIRMATION_REQUIRED");
        if(!Boolean.TRUE.equals(env.getProperty("eodiisseo.owned-runtime.enabled",Boolean.class,false)))
            throw new CollectionFailure("OWNED_SCHEMA_REQUIRED");
    }
    @Scheduled(fixedDelayString="${eodiisseo.disaster.collection.poll-ms:60000}")
    public void poll(){
        for(var provider:OfficialProviderClient.Provider.values()){
            String prefix="eodiisseo.disaster.collection."+provider.name().toLowerCase(Locale.ROOT).replace('_','-');
            if(!env.getProperty(prefix+".enabled",Boolean.class,false))continue;
            try{
                if(provider==OfficialProviderClient.Provider.WILDFIRE_MESSAGES)throw new CollectionFailure("INCREMENTAL_CONTRACT_REQUIRED");
                UUID source=UUID.fromString(env.getRequiredProperty(prefix+".source-id"));
                var cursor=db.one("select completed_through from owned_collection_cursor where source_id=?",source);
                LocalDate today=LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Seoul"));
                LocalDate initial=LocalDate.parse(env.getRequiredProperty(prefix+".initial-from"));
                LocalDate completed=cursor==null||cursor.get("completed_through")==null?null:((java.sql.Date)cursor.get("completed_through")).toLocalDate();
                var window=CollectionWindow.next(initial,completed,today);
                var outcome=service.collect(source,new OfficialProviderClient(provider,http::get),env.getRequiredProperty(prefix+".key"),window.from(),window.to(),
                    env.getRequiredProperty("eodiisseo.disaster.collection.owner"));
                statuses.put(provider,outcome.status());
            }catch(RuntimeException failure){
                statuses.put(provider,failure instanceof CollectionFailure safe?safe.code():"CONFIGURATION_INVALID");
                // Provider isolation. Service persists safe failure codes once a run is claimed; never log configuration values.
            }
        }
    }
    public void close(){http.close();}
}

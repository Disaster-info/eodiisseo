package com.disasterinfo.eodiisseo.collection.adapter;

import com.disasterinfo.eodiisseo.collection.service.CollectionService;
import com.disasterinfo.eodiisseo.ops.dto.OpsDto.Accepted;
import com.disasterinfo.eodiisseo.ops.port.ShelterRefreshGateway;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component @Profile("!test") @ConditionalOnProperty(name="COLLECTION_WORKER_ENABLED",havingValue="true")
public class CollectionShelterRefreshGateway implements ShelterRefreshGateway {
    private final CollectionService service;
    public CollectionShelterRefreshGateway(CollectionService service){this.service=service;}
    public Accepted request(UUID member,String source,String mode){return service.request(member,source,mode,true);}
}

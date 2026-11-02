package com.disasterinfo.eodiisseo.collection.scheduler;

import com.disasterinfo.eodiisseo.collection.service.CollectionService;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Profile("!test")
@ConditionalOnProperty(name="COLLECTION_WORKER_ENABLED",havingValue="true")
public class CollectionScheduler {
    private final CollectionService service;
    public CollectionScheduler(CollectionService service){this.service=service;}
    @Scheduled(fixedDelayString="${COLLECTION_POLL_INTERVAL_MS:1000}") public void poll(){service.workOne();}
    @Scheduled(fixedDelayString="${COLLECTION_SCHEDULE_INTERVAL_MS:60000}",initialDelayString="${COLLECTION_SCHEDULE_INTERVAL_MS:60000}")
    public void schedule(){service.enqueueDue();}
}

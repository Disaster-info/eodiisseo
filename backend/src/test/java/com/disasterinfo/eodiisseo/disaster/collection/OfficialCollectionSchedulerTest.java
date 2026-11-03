package com.disasterinfo.eodiisseo.disaster.collection;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class OfficialCollectionSchedulerTest {
    @Test void missingMessageIncrementalContractPreventsAnyRepeatedHttpOrDatabaseCollection(){
        var service=mock(OfficialCollectionService.class);var db=mock(OwnedDatabase.class);
        var env=new MockEnvironment().withProperty("eodiisseo.owned-runtime.enabled","true")
            .withProperty("eodiisseo.disaster.collection.owner","owned-disaster-development")
            .withProperty("eodiisseo.disaster.collection.wildfire-messages.enabled","true");
        try(var scheduler=new OfficialCollectionScheduler(service,db,env,Clock.systemUTC())){
            scheduler.poll();scheduler.poll();
            assertThat(scheduler.statusSnapshot()).containsEntry(OfficialProviderClient.Provider.WILDFIRE_MESSAGES,"INCREMENTAL_CONTRACT_REQUIRED");
            verifyNoInteractions(service,db);
        }
    }
    @Test void ownershipAttestationIsRequired(){
        assertThatThrownBy(()->new OfficialCollectionScheduler(mock(OfficialCollectionService.class),mock(OwnedDatabase.class),new MockEnvironment(),Clock.systemUTC()))
            .isInstanceOf(CollectionFailure.class);
    }
}

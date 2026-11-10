package com.disasterinfo.eodiisseo.collection.execution;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership;

class CollectionArchivePolicyTest {
    final CollectionArchivePolicy policy=new CollectionArchivePolicy(Duration.ofHours(1),"SYNTHETIC approval",Set.of("synthetic-secret-value"));
    @Test void originalsClonedAndHashedWithoutLoggingBody(){
        byte[] bytes="SYNTHETIC raw body".getBytes(StandardCharsets.UTF_8);var body=policy.inspect(bytes);bytes[0]=0;
        assertFalse(body.omitted());assertEquals("SYNTHETIC raw body",new String(body.bytes(),StandardCharsets.UTF_8));assertEquals(64,body.sha256().length());assertFalse(body.toString().contains("raw body"));
    }
    @Test void sensitiveOrInvalidBodiesAreOmittedEntirely(){
        for(String raw:List.of("synthetic-secret-value","{\"authKey\":\"not-for-storage\"}","https://example.invalid/?signed=secret","Authorization: Bearer secret")){
            var body=policy.inspect(raw.getBytes(StandardCharsets.UTF_8));assertTrue(body.omitted());assertNull(body.bytes());
        }
        assertTrue(policy.inspect(new byte[]{(byte)0xff}).omitted());
    }
    @Test void explicitRetentionAndAccessApprovalRequired(){
        assertThrows(IllegalArgumentException.class,()->new CollectionArchivePolicy(Duration.ZERO,"approval",Set.of()));
        assertThrows(IllegalArgumentException.class,()->new CollectionArchivePolicy(Duration.ofHours(1),"",Set.of()));
    }
    @Test void jsonEscapesCannotHideSensitiveFields(){
        String escaped="{\"service"+"\\u004b"+"ey\":\"SYNTHETIC private value\"}";
        assertTrue(policy.inspect(escaped.getBytes(StandardCharsets.UTF_8)).omitted());
        assertThrows(IllegalArgumentException.class,()->new CollectionArchivePolicy(Duration.ofHours(1),"https://example.invalid/?authKey=secret",Set.of()));
    }
    @Test void disabledCoordinatorDoesNotClaimOrAccessDatabase(){
        var ownership=mock(CollectionOwnership.class);var store=mock(CollectionExecutionStore.class);
        var coordinator=new CollectionCoordinator(ownership,store,List.of(),new CollectionCoordinator.Policy(false,1,1,1,Duration.ofSeconds(1),Duration.ZERO),policy);
        assertEquals(CollectionExecutionStore.State.DISABLED,coordinator.collect(UUID.randomUUID(),LocalDate.now(),LocalDate.now(),1).state());
        verifyNoInteractions(ownership,store);
    }
}
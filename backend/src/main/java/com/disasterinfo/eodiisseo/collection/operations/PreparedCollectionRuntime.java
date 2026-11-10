package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.collection.execution.CollectionCoordinator;
import java.time.LocalDate;
import java.util.*;
/** Assembled for preparation only. No scheduler, arm API, coordinator accessor or HTTP execution path. */
public final class PreparedCollectionRuntime {
    private final CollectionCoordinator coordinator;
    private final CollectionReadiness readiness;private final Set<UUID> sources;
    public PreparedCollectionRuntime(CollectionCoordinator coordinator,CollectionReadiness readiness,Set<UUID> sources){
        this.coordinator=Objects.requireNonNull(coordinator);this.readiness=Objects.requireNonNull(readiness);this.sources=Set.copyOf(sources);
        if(!readiness.inspect(sources).prepared())throw new IllegalStateException("Collection preparation prerequisites are incomplete");
    }
    public CollectionReadiness.Report inspect(){return readiness.inspect(sources);}
    public CollectionCoordinator.Result collect(UUID source,LocalDate from,LocalDate to,int rows){
        // 4B must supply a separately reviewed legacy-worker exclusion/arming protocol. Flags cannot bypass it.
        return new CollectionCoordinator.Result(null,com.disasterinfo.eodiisseo.collection.execution.CollectionExecutionStore.State.DISABLED,"CUTOVER_NOT_AUTHORIZED_IN_4A");
    }
}
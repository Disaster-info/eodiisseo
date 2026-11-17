package com.disasterinfo.eodiisseo.collection.operations;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CollectionProcessBoundaryTest {
 final Instant now=Instant.now();
 Map<CollectionLoginPlane.Kind,String> roles(){return Map.of(CollectionLoginPlane.Kind.WORKER,"worker",CollectionLoginPlane.Kind.GATEWAY,"gateway",CollectionLoginPlane.Kind.OPS,"ops");}
 CollectionProcessBoundary.Snapshot snapshot(boolean mixed){var p=new HashSet<CollectionProcessBoundary.Process>();int id=1;for(var e:roles().entrySet())p.add(new CollectionProcessBoundary.Process(id++,e.getKey(),mixed?"shared":e.getValue(),"a".repeat(64),Set.of(e.getValue())));return new CollectionProcessBoundary.Snapshot(p,now.minusSeconds(1),now.plusSeconds(30),true,true);}
 @Test void sameJvmOrSharedOsPrincipalIsNotIsolation(){assertTrue(CollectionProcessBoundary.blockers(snapshot(true),roles(),now,false).contains("PROCESS_OR_CREDENTIAL_PLANE_MIXED"));}
 @Test void syntheticInventoryCannotBecomeProductionEvidence(){assertFalse(CollectionProcessBoundary.blockers(snapshot(false),roles(),now,true).isEmpty());assertTrue(CollectionProcessBoundary.blockers(snapshot(false),roles(),now,false).isEmpty());}
 @Test void missingAndExpiredInventoryFailClosed(){assertFalse(CollectionProcessBoundary.blockers(null,roles(),now,true).isEmpty());assertFalse(CollectionProcessBoundary.blockers(snapshot(false),roles(),now.plusSeconds(60),false).isEmpty());}
 @Test void auditConstructorCannotDisableTlsForAnOrdinaryDatabase(){assertThrows(IllegalArgumentException.class,()->new CollectionLoginPool("jdbc:postgresql://127.0.0.1:55435/postgres","worker",new char[32],"insecure",1));}
 @Test void repeatedDatabaseRoleIsNotIsolation(){assertTrue(CollectionProcessBoundary.blockers(snapshot(false),Map.of(CollectionLoginPlane.Kind.WORKER,"same",CollectionLoginPlane.Kind.GATEWAY,"same",CollectionLoginPlane.Kind.OPS,"ops"),now,false).contains("DISTINCT_DATABASE_ROLES_REQUIRED"));}
}

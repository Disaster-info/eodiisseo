package com.disasterinfo.eodiisseo.collection.ownership;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.*;

class CollectionOwnershipTest {
    final Memory store = new Memory();
    final CollectionOwnership service = new CollectionOwnership(store, new CollectionContractRegistry(), Duration.ofSeconds(10));
    final UUID source = UUID.randomUUID(), target = UUID.randomUUID();
    Assignment assignment(UUID id, UUID target, String contract, String alias) {
        return new Assignment(id, alias, target, contract, "quota-approved-ref", 1, CollectionOwnership.OWNER, "operator-review-ref", true);
    }
    @BeforeEach void setup() { store.assignments.put(source, assignment(source,target,"MOIS_MESSAGES","SAFETYDATA_DISASTER_MESSAGE")); }
    @Test void registrySeparatesContracts() {
        var registry = new CollectionContractRegistry(); assertEquals(7,registry.contracts().size());
        assertNotEquals(registry.require("KMA_PORTAL_EQK_MSG","KMA_EARTHQUAKE"), registry.require("KMA_HUB_EQK_MSG","KMA_EARTHQUAKE"));
        assertThrows(IllegalArgumentException.class,()->registry.require("MOIS_MESSAGES","KMA_WARNING"));
    }
    @Test void claimSnapshotAndDuplicateTarget() {
        var claim=service.claim(source,"LIVE"); assertEquals(1,claim.fence()); assertEquals(store.assignments.get(source),claim.assignment());
        var alias=UUID.randomUUID(); store.assignments.put(alias,assignment(alias,target,"MOIS_MESSAGES","MOIS_DISASTER_MESSAGES"));
        assertThrows(IllegalStateException.class,()->service.claim(alias,"LIVE"));
        assertThrows(IllegalStateException.class,()->service.claim(source,"LIVE"));
    }
    @Test void independentApisCanReserve() {
        service.claim(source,"LIVE"); var second=UUID.randomUUID();
        store.assignments.put(second,assignment(second,UUID.randomUUID(),"KMA_RAIN_CODES","KMA_HEAVY_RAIN"));
        assertNotNull(service.claim(second,"LIVE"));
    }
    @Test void unknownLegacyRunHeldEvenAfterExpiry() {
        store.unknownTargets.add(target); store.time=store.time.plusSeconds(100000);
        assertThrows(IllegalStateException.class,()->service.claim(source,"LIVE")); assertTrue(store.unknownTargets.contains(target));
    }
    @Test void noAssignmentOrQuiescenceFailsClosed() {
        assertThrows(NullPointerException.class,()->service.claim(UUID.randomUUID(),"LIVE"));
        var a=store.assignments.get(source);
        store.assignments.put(source,new Assignment(source,a.alias(),target,a.contract(),a.quotaScope(),1,a.owner(),a.approvalReference(),false));
        assertThrows(IllegalStateException.class,()->service.claim(source,"LIVE"));
    }
    @Test void heartbeatAndExpiryBoundary() {
        var claim=service.claim(source,"LIVE"); store.time=store.time.plusSeconds(9); service.heartbeat(claim);
        assertFalse(service.expireReservation(claim)); store.time=store.time.plusSeconds(10);
        assertThrows(IllegalStateException.class,()->service.heartbeat(claim)); assertTrue(service.expireReservation(claim));
        assertEquals(Outcome.FAILED,store.outcomes.get(claim.run()));
    }
    @Test void oldFenceCannotWriteOrFinishSuccessor() {
        var old=service.claim(source,"LIVE"); store.time=store.time.plusSeconds(10); service.expireReservation(old);
        var next=service.claim(source,"LIVE"); assertEquals(2,next.fence());
        assertThrows(IllegalStateException.class,()->service.finish(old,Outcome.SUCCESS,()->store.writes++));
        assertThrows(IllegalStateException.class,()->service.expireReservation(old)); assertEquals(0,store.writes);
        service.finish(next,Outcome.SUCCESS,()->store.writes++); assertEquals(1,store.writes);
    }
    @Test void failedWriteRollsBackAndRetainsClaim() {
        var claim=service.claim(source,"LIVE");
        assertThrows(IllegalArgumentException.class,()->service.finish(claim,Outcome.SUCCESS,()->{store.writes++;throw new IllegalArgumentException();}));
        assertEquals(0,store.writes); assertNull(store.outcomes.get(claim.run())); service.heartbeat(claim);
    }
    @Test void leaseLostDuringWriteRollsBack() {
        var claim=service.claim(source,"LIVE");
        assertThrows(IllegalStateException.class,()->service.finish(claim,Outcome.SUCCESS,()->{store.writes++;store.time=store.time.plusSeconds(10);}));
        assertEquals(0,store.writes); assertNull(store.outcomes.get(claim.run()));
    }
    @Test void assignmentEpochChangeFencesExistingClaim() {
        var claim=service.claim(source,"LIVE"); var a=claim.assignment();
        store.assignments.put(source,new Assignment(source,a.alias(),target,a.contract(),a.quotaScope(),2,a.owner(),a.approvalReference(),true));
        assertThrows(IllegalStateException.class,()->service.heartbeat(claim));
        assertThrows(IllegalStateException.class,()->service.finish(claim,Outcome.SUCCESS,()->store.writes++));
    }
    @Test void simultaneousTargetClaimsHaveOneWinner() throws Exception {
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> task=()->{start.await();try{service.claim(source,"LIVE");return true;}catch(IllegalStateException held){return false;}};
            var first=executor.submit(task);var second=executor.submit(task);start.countDown();
            assertNotEquals(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,store.active.size());
        }
    }
    /** Transactional fake, not PostgreSQL or evidence of database lock correctness. */
    static final class Memory implements Store, Transaction {
        Map<UUID,Assignment> assignments=new HashMap<>(); Set<UUID> unknownTargets=new HashSet<>();
        Map<UUID,Claim> active=new HashMap<>(); Map<UUID,Instant> until=new HashMap<>();
        Map<UUID,Long> fences=new HashMap<>(); Map<UUID,Outcome> outcomes=new HashMap<>();
        Instant time=Instant.parse("2026-11-10T00:00:00Z"); int writes;
        public synchronized <T>T transaction(Function<Transaction,T> action) {
            var a=new HashMap<>(active); var u=new HashMap<>(until); var f=new HashMap<>(fences);var o=new HashMap<>(outcomes);int w=writes;
            try{return action.apply(this);}catch(RuntimeException e){active=a;until=u;fences=f;outcomes=o;writes=w;throw e;}
        }
        public Assignment assignment(UUID s){return assignments.get(s);}
        public void lock(Assignment a,String mode){}
        public boolean blocked(Assignment a,String mode){return unknownTargets.contains(a.target())||active.containsKey(a.target());}
        public Instant now(){return time;}
        public long nextFence(UUID t,String mode){return fences.merge(t,1L,Long::sum);}
        public void insert(Claim c,Instant expiry){active.put(c.assignment().target(),c);until.put(c.run(),expiry);}
        public void assertCurrent(Claim c,boolean live){
            if(!c.equals(active.get(c.assignment().target())) || !c.assignment().equals(assignments.get(c.assignment().source())) ||
                (live&&!until.get(c.run()).isAfter(time)))throw new IllegalStateException("Stale ownership");
        }
        public Instant leaseUntil(Claim c){return until.get(c.run());}
        public void renew(Claim c,Instant expiry){until.put(c.run(),expiry);}
        public void finish(Claim c,Outcome outcome){outcomes.put(c.run(),outcome);active.remove(c.assignment().target());until.remove(c.run());}
    }
}
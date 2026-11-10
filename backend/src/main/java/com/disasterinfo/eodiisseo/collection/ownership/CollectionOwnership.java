package com.disasterinfo.eodiisseo.collection.ownership;

import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Inactive infrastructure: deliberately not a Spring bean until DB migration and rollout approval. */
public final class CollectionOwnership {
    public static final String OWNER = "COMMON_COLLECTION_V1";
    public record Assignment(UUID source, String alias, UUID target, String contract,
            String quotaScope, long epoch, String owner, String approvalReference,
            boolean legacyWorkersQuiesced) {
        public Assignment {
            Objects.requireNonNull(source); Objects.requireNonNull(target);
            for (var value : List.of(alias, contract, quotaScope, owner, approvalReference))
                if (value.isBlank()) throw new IllegalArgumentException("Empty assignment identity");
            if (epoch < 1) throw new IllegalArgumentException("Invalid assignment epoch");
        }
    }
    public record Claim(UUID run, UUID claimId, Assignment assignment, String mode, long fence) {
        public Claim {
            Objects.requireNonNull(run); Objects.requireNonNull(claimId); Objects.requireNonNull(assignment);
            if (!"LIVE".equals(mode) || fence < 1) throw new IllegalArgumentException("Invalid claim");
        }
    }
    public enum Outcome { SUCCESS, FAILED }
    /**
     * Adapter must run each callback in one DB transaction. Lock order: global gate, target, source, run.
     * lock() includes every alias source bound to that target; unknown/orphan legacy runs and locks hold it.
     * Time must be fresh database clock_timestamp(), not transaction start time.
     * generation is persistent per target/mode, never reset when a lease is released.
     * assertCurrent compares entire immutable snapshot, owner, epoch, run, claim and fence, and locks it.
     * It rejects changed assignments and requires lease > database time when live=true.
     * Expiry is ONLY for ownership-only reservations; future in-flight HTTP requires separate recovery.
     * All exceptions roll back writes, fence allocation, run state and lease changes together.
     */
    public interface Store {
        <T> T transaction(Function<Transaction, T> action);
    }
    public interface Transaction {
        Assignment assignment(UUID source);
        void lock(Assignment assignment, String mode);
        boolean blocked(Assignment assignment, String mode);
        Instant now();
        long nextFence(UUID target, String mode);
        void insert(Claim claim, Instant leaseUntil);
        void assertCurrent(Claim claim, boolean live);
        Instant leaseUntil(Claim claim);
        void renew(Claim claim, Instant leaseUntil);
        void finish(Claim claim, Outcome outcome);
        default void assertNoUncertainRequest(Claim claim) {}
    }
    private final Store store;
    private final CollectionContractRegistry registry;
    private final Duration lease;
    public CollectionOwnership(Store store, CollectionContractRegistry registry, Duration lease) {
        this.store = Objects.requireNonNull(store); this.registry = Objects.requireNonNull(registry);
        if (lease.isZero() || lease.isNegative() || lease.compareTo(Duration.ofMinutes(30)) > 0)
            throw new IllegalArgumentException("Lease must be positive and bounded");
        this.lease = lease;
    }
    public Claim claim(UUID source, String mode) {
        if (!"LIVE".equals(mode)) throw new IllegalArgumentException("Unsupported execution mode");
        return store.transaction(tx -> {
            var a = Objects.requireNonNull(tx.assignment(source), "Approved assignment required");
            registry.require(a.contract(), a.alias());
            if (!a.source().equals(source) || !OWNER.equals(a.owner()) || !a.legacyWorkersQuiesced())
                throw new IllegalStateException("Ownership rollout not approved");
            tx.lock(a, mode);
            // Re-read after locking: assignment changes must never race a claim.
            if (!a.equals(tx.assignment(source)) || tx.blocked(a, mode))
                throw new IllegalStateException("Target held or assignment changed");
            var claim = new Claim(UUID.randomUUID(), UUID.randomUUID(), a, mode, tx.nextFence(a.target(), mode));
            tx.insert(claim, tx.now().plus(lease)); return claim;
        });
    }
    public void heartbeat(Claim claim) {
        store.transaction(tx -> { tx.lock(claim.assignment(), claim.mode()); tx.assertCurrent(claim, true);
            tx.renew(claim, tx.now().plus(lease)); return null; });
    }
    /** Same-manager transactional writes only. External I/O is forbidden. */
    public void finish(Claim claim, Outcome outcome, Runnable transactionalWrites) {
        Objects.requireNonNull(outcome); Objects.requireNonNull(transactionalWrites);
        finishDeciding(claim,()->{transactionalWrites.run();return outcome;});
    }
    /** Domain outcome is decided inside the same final fencing transaction, not after commit. */
    public Outcome finishDeciding(Claim claim,java.util.function.Supplier<Outcome> transactionalWrites){
        Objects.requireNonNull(transactionalWrites);
        return store.transaction(tx->{
            tx.lock(claim.assignment(),claim.mode());tx.assertCurrent(claim,true);tx.assertNoUncertainRequest(claim);
            Outcome outcome=Objects.requireNonNull(transactionalWrites.get());
            tx.assertCurrent(claim,true);tx.assertNoUncertainRequest(claim);tx.finish(claim,outcome);return outcome;
        });
    }
    public boolean expireReservation(Claim claim) {
        return store.transaction(tx -> { tx.lock(claim.assignment(), claim.mode()); tx.assertCurrent(claim, false);
            tx.assertNoUncertainRequest(claim);
            if (tx.leaseUntil(claim).isAfter(tx.now())) return false;
            tx.finish(claim, Outcome.FAILED); return true; });
    }
}
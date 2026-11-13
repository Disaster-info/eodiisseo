package com.disasterinfo.eodiisseo.collection.operations;

import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.function.Supplier;

/** Trusted in-process gateway, NOT a Worker-facing SQL/RPC API. Use a separate least-privilege gateway DataSource. */
public final class ProductionCollectionWriteGateway {
    public enum Phase { CLAIM, EXECUTION, PROJECTION, RECEIPT }
    public static final class Permit {
        private final UUID worker;private final String capability;
        public Permit(UUID worker,String capability){this.worker=Objects.requireNonNull(worker);this.capability=Objects.requireNonNull(capability);if(capability.length()<32||capability.length()>256)throw new IllegalArgumentException("Bounded capability required");}
        @Override public String toString(){return "ProductionWorkerPermit[redacted]";}
    }
    private final JdbcTemplate jdbc;private final JpaTransactionManager manager;private final TransactionTemplate tx;
    public ProductionCollectionWriteGateway(JdbcTemplate jdbc,JpaTransactionManager manager){
        this.jdbc=Objects.requireNonNull(jdbc);this.manager=Objects.requireNonNull(manager);
        if(manager.getDataSource()!=jdbc.getDataSource()||!(manager.getEntityManagerFactory() instanceof EntityManagerFactoryInfo info)||info.getDataSource()!=jdbc.getDataSource())throw new IllegalArgumentException("Same physical JPA/JDBC DataSource required");
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** No HTTP/model/send operation belongs in this short DB-only callback. Existing repositories participate. */
    public <T>T write(Permit permit,UUID source,Phase phase,Claim claim,Supplier<T> approvedDatabaseWork){
        Objects.requireNonNull(permit);Objects.requireNonNull(source);Objects.requireNonNull(phase);Objects.requireNonNull(approvedDatabaseWork);
        if((phase==Phase.CLAIM)!=(claim==null)||claim!=null&&!source.equals(claim.assignment().source()))throw new IllegalArgumentException("Exact source and claim required");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Gateway requires a dedicated short transaction boundary");
        return tx.execute(status->{
            jdbc.queryForList("select collection_production.open_context(?,?,?,?,?,?,?)",permit.worker,permit.capability,source,phase.name(),claim==null?null:claim.run(),claim==null?null:claim.claimId(),claim==null?null:claim.fence());
            var holder=(EntityManagerHolder)TransactionSynchronizationManager.getResource(manager.getEntityManagerFactory());
            if(holder==null||((Number)holder.getEntityManager().createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue()!=jdbc.queryForObject("select pg_backend_pid()",Integer.class))throw new IllegalStateException("Physical transaction mismatch");
            T result=approvedDatabaseWork.get();holder.getEntityManager().flush();
            jdbc.queryForList("select collection_production.close_context()");
            // Keep the deferred authorization check queued until the actual PostgreSQL COMMIT.
            return result;
        });
    }
    public static List<String> preparationBlockers(JdbcTemplate jdbc){
        try{
            if(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2026111301' and success",Long.class)!=1)return List.of("PRODUCTION_GATEWAY_MIGRATION_REQUIRED");
            String state=jdbc.queryForObject("select collection_production.status()",String.class);
            return "SEALED_NOT_EXECUTION_AUTHORIZATION".equals(state)?List.of("EXPLICIT_PRODUCTION_EXECUTION_APPROVAL_STILL_REQUIRED"):List.of(state);
        }catch(RuntimeException unavailable){return List.of("PRODUCTION_GATEWAY_POLICY_OR_PRIVILEGES_REQUIRED");}
    }
}
package com.disasterinfo.eodiisseo.collection.operations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.*;
import java.util.*;
/** Durable receipt shares the Gateway's physical transaction. Failures are a separate audit,
 * never a success marker or an automatic external retry. No Bean/default retention policy. */
public final class JdbcCollectionCommandJournal implements CollectionWorkerCommandBoundary.Journal {
 private final JdbcTemplate jdbc;private final UUID worker;private final TransactionTemplate failures;
 public JdbcCollectionCommandJournal(JdbcTemplate jdbc,JpaTransactionManager manager,UUID worker){
  this.jdbc=Objects.requireNonNull(jdbc);this.worker=Objects.requireNonNull(worker);
  if(jdbc.getDataSource()!=manager.getDataSource())throw new IllegalArgumentException("Same journal transaction required");
  failures=new TransactionTemplate(manager);failures.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 @Override public void verifyWorker(UUID actual){if(!worker.equals(actual))throw new IllegalStateException("Journal worker binding mismatch");}
 @Override public javax.sql.DataSource dataSource(){return jdbc.getDataSource();}
 private void inGateway(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(dataSource()))throw new IllegalStateException("Gateway transaction required");}
 @Override public boolean reserve(UUID id,String digest){throw new IllegalStateException("Signed command deadline required");}
 @Override public boolean reserve(UUID id,String digest,java.time.Instant until){inGateway();return Boolean.TRUE.equals(jdbc.queryForObject("select collection_production.reserve_command(?,?,?)",Boolean.class,id,digest,java.sql.Timestamp.from(until)));}
 @Override public void completed(UUID id,String digest){inGateway();jdbc.queryForList("select collection_production.complete_command(?,?)",id,digest);}
 @Override public void failed(UUID id,String digest,String outcome){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Failure audit must follow rollback");failures.execute(s->{jdbc.queryForList("select collection_production.record_command_failure(?,?,?,?)",worker,id,digest,outcome);return null;});}
}

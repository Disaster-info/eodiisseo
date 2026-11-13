package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.collection.execution.CollectionCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProductionCollectionBoundaryTest {
 @Test void mismatchedTransactionDataSourceIsRejected(){var j=new JdbcTemplate(mock(DataSource.class));var m=mock(JpaTransactionManager.class);when(m.getDataSource()).thenReturn(mock(DataSource.class));assertThrows(IllegalArgumentException.class,()->new ProductionCollectionWriteGateway(j,m));}
 @Test void permitNeverRendersCapability(){String secret="SYNTHETIC capability "+UUID.randomUUID();assertFalse(new ProductionCollectionWriteGateway.Permit(UUID.randomUUID(),secret).toString().contains(secret));assertThrows(IllegalArgumentException.class,()->new ProductionCollectionWriteGateway.Permit(UUID.randomUUID(),"short"));}
 @Test void splitControlDataSourcesCannotBeAssembled(){assertThrows(IllegalArgumentException.class,()->new ProductionCollectionCutover(new JdbcTemplate(mock(DataSource.class)),mock(CollectionOperationsService.class),mock(JdbcCollectionAssurance.class),mock(VerifiedCollectionOperationalEvidence.class)));}
 @Test void missingLiveFleetProofBlocksSealBeforeAnyDatabaseMutation(){var ds=mock(DataSource.class);var jdbc=new JdbcTemplate(ds);var ops=mock(CollectionOperationsService.class);var assurance=mock(JdbcCollectionAssurance.class);var evidence=mock(VerifiedCollectionOperationalEvidence.class);when(ops.usesDataSource(ds)).thenReturn(true);when(assurance.usesDataSource(ds)).thenReturn(true);var c=new ProductionCollectionCutover(jdbc,ops,assurance,evidence);assertThrows(IllegalStateException.class,()->c.seal(null,UUID.randomUUID(),List.of(UUID.randomUUID()),Set.of(UUID.randomUUID()),Instant.now().plusSeconds(10),"SYNTHETIC:TEST"));verify(ops,never()).withOps(any(),any());verifyNoInteractions(ds);}
 @Test void preparedRuntimeCannotStartAWorkerEvenWhenPrepared(){var coordinator=mock(CollectionCoordinator.class);var readiness=mock(CollectionReadiness.class);var sources=Set.of(UUID.randomUUID());when(readiness.inspect(sources)).thenReturn(new CollectionReadiness.Report(true,false,List.of()));var runtime=new PreparedCollectionRuntime(coordinator,readiness,sources);assertEquals(com.disasterinfo.eodiisseo.collection.execution.CollectionExecutionStore.State.DISABLED,runtime.collect(sources.iterator().next(),LocalDate.now(),LocalDate.now(),1).state());verifyNoInteractions(coordinator);}
}
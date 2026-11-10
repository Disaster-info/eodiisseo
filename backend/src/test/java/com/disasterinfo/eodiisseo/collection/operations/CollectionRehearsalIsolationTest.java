package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.disaster.projection.CollectionPreparationConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CollectionRehearsalIsolationTest {
    @Test void noPropertyRegistersAnArmingOrRehearsalBean(){new ApplicationContextRunner().withUserConfiguration(CollectionPreparationConfiguration.class,CollectionOperationsController.class).withPropertyValues("eodiisseo.collection.operations.rehearsal-enabled=true").run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(CollectionCutoverRehearsal.class).isEmpty());assertTrue(context.getBeansOfType(PreparedCollectionRuntime.class).isEmpty());assertTrue(context.getBeansOfType(com.disasterinfo.eodiisseo.collection.execution.CollectionCoordinator.class).isEmpty());assertTrue(context.getBeansOfType(CollectionOperationsController.class).isEmpty());});}
    @Test void productionModeRejectedBeforeAnyDatabaseAccess(){assertThrows(IllegalArgumentException.class,()->new CollectionCutoverRehearsal(null,null,null,null,null,Set.of(),List.of(),null,null,null,CollectionOperationMode.PRODUCTION,Duration.ofSeconds(1)));}
    @ParameterizedTest @ValueSource(strings={"jdbc:postgresql://127.0.0.1:55433/postgres","jdbc:postgresql://127.0.0.1:55433/collection4a_01234567890123456789012345678901","jdbc:postgresql://127.0.0.1:5432/collection4b1_01234567890123456789012345678901"})
    void ineligibleDatabaseIsRejectedUsingMetadataOnly(String url)throws Exception{DataSource ds=mock(DataSource.class);Connection c=mock(Connection.class);DatabaseMetaData metadata=mock(DatabaseMetaData.class);when(ds.getConnection()).thenReturn(c);when(c.getMetaData()).thenReturn(metadata);when(metadata.getURL()).thenReturn(url);assertThrows(IllegalArgumentException.class,()->new CollectionCutoverRehearsal(new JdbcTemplate(ds),null,null,null,null,Set.of(),List.of(),null,null,null,CollectionOperationMode.DISPOSABLE_AUDIT,Duration.ofSeconds(1)));verify(c,atLeastOnce()).close();}
}
package com.disasterinfo.eodiisseo.collection.ownership;

import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Transaction wiring guards only. Does not execute or validate PostgreSQL SQL. */
class JdbcCollectionOwnershipStoreTest {
    DataSource source=mock(DataSource.class);
    DataSourceTransactionManager manager=mock(DataSourceTransactionManager.class);
    JdbcCollectionOwnershipStore store;
    @BeforeEach void setup(){
        when(manager.getDataSource()).thenReturn(source);
        store=new JdbcCollectionOwnershipStore(new JdbcTemplate(source),manager);
        clearInvocations(manager);
    }
    @Test void anotherDataSourceManagerRejected() {
        var other=mock(DataSourceTransactionManager.class);
        when(other.getDataSource()).thenReturn(mock(DataSource.class));
        assertThrows(IllegalArgumentException.class,()->new JdbcCollectionOwnershipStore(new JdbcTemplate(source),other));
    }
    @Test void unknownAmbientIsolationRejected() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class,()->store.transaction(tx->null));verifyNoInteractions(manager,source);
    }
    @Test void jpaCannotRelyOnlyOnManagerDataSourceEquality(){
        var jpa=mock(org.springframework.orm.jpa.JpaTransactionManager.class);
        var factory=mock(jakarta.persistence.EntityManagerFactory.class,withSettings().extraInterfaces(org.springframework.orm.jpa.EntityManagerFactoryInfo.class));
        when(jpa.getDataSource()).thenReturn(source);when(jpa.getEntityManagerFactory()).thenReturn(factory);
        when(((org.springframework.orm.jpa.EntityManagerFactoryInfo)factory).getDataSource()).thenReturn(mock(DataSource.class));
        assertThrows(IllegalArgumentException.class,()->new JdbcCollectionOwnershipStore(new JdbcTemplate(source),jpa));
    }
    @Test void unknownJpaFactoryIdentityIsRejected(){
        var jpa=mock(org.springframework.orm.jpa.JpaTransactionManager.class);
        when(jpa.getDataSource()).thenReturn(source);when(jpa.getEntityManagerFactory()).thenReturn(mock(jakarta.persistence.EntityManagerFactory.class));
        assertThrows(IllegalArgumentException.class,()->new JdbcCollectionOwnershipStore(new JdbcTemplate(source),jpa));
    }
    @AfterEach void cleanup(){TransactionSynchronizationManager.clear();}
    @Test void mismatchedTransactionResourceRejectedBeforeCallback() {
        var status=new SimpleTransactionStatus();when(manager.getTransaction(any())).thenReturn(status);
        assertThrows(IllegalStateException.class,()->store.transaction(tx->{fail("Must not write outside JDBC transaction");return null;}));
        verify(manager).rollback(status);verify(manager,never()).commit(any());verifyNoInteractions(source);
    }
    @Test void readOnlyOuterTransactionRejected() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class,()->store.transaction(tx->null));verifyNoInteractions(manager,source);
    }
    @Test void incompatibleOuterIsolationRejected() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(IllegalStateException.class,()->store.transaction(tx->null));verifyNoInteractions(manager,source);
    }
    @Test void explicitReadCommittedRequested() {
        when(manager.getTransaction(any())).thenAnswer(call->{
            TransactionDefinition definition=call.getArgument(0);
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,definition.getIsolationLevel());
            return new SimpleTransactionStatus();
        });
        assertThrows(IllegalStateException.class,()->store.transaction(tx->null));verify(manager).getTransaction(any());
    }
}
package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CollectionWorkerCommandBoundaryTest {
 final DataSource ds=mock(DataSource.class);final JdbcTemplate jdbc=new JdbcTemplate(ds);final JpaTransactionManager manager=mock(JpaTransactionManager.class);
 final ProductionCollectionWriteGateway gateway=mock(ProductionCollectionWriteGateway.class);final CollectionWorkerCommandBoundary.Authorizer auth=mock(CollectionWorkerCommandBoundary.Authorizer.class);final CollectionWorkerCommandBoundary.Journal journal=mock(CollectionWorkerCommandBoundary.Journal.class);
 final Instant now=Instant.now();final UUID source=UUID.randomUUID(),run=UUID.randomUUID(),claim=UUID.randomUUID();
 @BeforeEach void wiring(){var emf=mock(jakarta.persistence.EntityManagerFactory.class,withSettings().extraInterfaces(EntityManagerFactoryInfo.class));when(manager.getDataSource()).thenReturn(ds);when(manager.getEntityManagerFactory()).thenReturn(emf);when(((EntityManagerFactoryInfo)emf).getDataSource()).thenReturn(ds);when(gateway.usesDataSource(ds)).thenReturn(true);when(journal.dataSource()).thenReturn(ds);}
 CollectionWorkerCommandBoundary boundary(){return new CollectionWorkerCommandBoundary(jdbc,manager,gateway,auth,journal,Duration.ofSeconds(30),Clock.fixed(now,ZoneOffset.UTC));}
 CollectionWorkerCommandBoundary.Heartbeat request(){return new CollectionWorkerCommandBoundary.Heartbeat(UUID.randomUUID(),UUID.randomUUID(),source,run,claim,1,1,now.plusSeconds(10),new byte[64]);}
 Claim claim(UUID scope){return new Claim(run,claim,new Assignment(scope,"alias",UUID.randomUUID(),"KMA_PORTAL_EQK_MSG","quota",1,"COMMON_COLLECTION_V1","REVIEW:EXPLICIT",true),"LIVE",1);}
 @Test void missingAuthorityNeverCallsGatewayOrJournal(){var b=boundary();var q=request();when(auth.require(q)).thenThrow(new IllegalStateException("No reviewed channel"));assertThrows(IllegalStateException.class,()->b.heartbeat(q));verify(gateway,never()).write(any(),any(),any(),any(),any());verify(journal,never()).reserve(any(),any());}
 @Test void mismatchedScopeIsRejectedBeforeGateway(){var b=boundary();var q=request();when(auth.require(q)).thenReturn(new CollectionWorkerCommandBoundary.Authorization(claim(UUID.randomUUID()),new ProductionCollectionWriteGateway.Permit(UUID.randomUUID(),"x".repeat(64)),1,now.plusSeconds(10)));assertThrows(IllegalStateException.class,()->b.heartbeat(q));verify(gateway,never()).write(any(),any(),any(),any(),any());}
 @Test void splitJournalDataSourceIsRejected(){when(journal.dataSource()).thenReturn(mock(DataSource.class));assertThrows(IllegalArgumentException.class,this::boundary);}
 @Test void signatureAndPayloadAreBoundedAndDefensivelyCopied(){var sig=new byte[64];sig[0]=7;var q=new CollectionWorkerCommandBoundary.Heartbeat(UUID.randomUUID(),UUID.randomUUID(),source,run,claim,1,1,now.plusSeconds(5),sig);sig[0]=0;assertEquals(7,q.signature()[0]);var copy=q.signature();copy[0]=0;assertEquals(7,q.signature()[0]);assertEquals("WorkerHeartbeat[redacted]",q.toString());assertThrows(IllegalArgumentException.class,()->new CollectionWorkerCommandBoundary.Heartbeat(UUID.randomUUID(),UUID.randomUUID(),source,run,claim,0,1,now,new byte[64]));}
}

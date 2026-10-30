package com.disasterinfo.eodiisseo.ops.service;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.ops.repository.OpsRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsServiceTest {
    @Test void rejectsInvalidPagingAndEmptyOrInvertedIntervals() {
        assertThrows(ApiProblem.class,()->OpsService.validatePage(0,101,null,null));
        var t=Instant.parse("2026-10-16T03:00:00Z");assertThrows(ApiProblem.class,()->OpsService.validatePage(0,20,t,t));
        assertThrows(ApiProblem.class,()->OpsService.validatePage(-1,20,null,null));
    }
    @Test void productionCannotReadMockData() {
        var env=new MockEnvironment();env.setActiveProfiles("production");
        var service=new OpsService(new OpsRepository(mock(OwnedDatabase.class)),env,mock(ObjectProvider.class));
        assertEquals(400,assertThrows(ApiProblem.class,()->service.status("MOCK")).status());
    }
    @Test void absentCollectorIsNotReportedAsAccepted() {
        var db=mock(OwnedDatabase.class);when(db.tableExists("data_source")).thenReturn(true);
        var service=new OpsService(new OpsRepository(db),new MockEnvironment(),mock(ObjectProvider.class));
        assertEquals(503,assertThrows(ApiProblem.class,()->service.refresh(UUID.randomUUID(),null,"LIVE")).status());
    }
    @Test void arbitraryErrorBodiesNeverBecomeClientMessages() {
        assertFalse(OpsService.safeMessage("serviceKey=SECRET&accessToken=SECRET").contains("SECRET"));
        assertEquals("UNKNOWN_ERROR",OpsService.safeIdentifier("serviceKey=SECRET","UNKNOWN_ERROR"));
    }
}

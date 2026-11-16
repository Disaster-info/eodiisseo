package com.disasterinfo.eodiisseo.checklist.service;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.checklist.repository.ChecklistRepository;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.object;

class ChecklistServiceTest {
    @Test void rejectsAnItemFromAnotherTemplateBeforeWritingCompletion() {
        var db=mock(OwnedDatabase.class);var service=new ChecklistService(new ChecklistRepository(db),Clock.systemUTC());
        var member=UUID.randomUUID();var template=UUID.randomUUID();var item=UUID.randomUUID();
        when(db.one(startsWith("select i.item_id"),eq(item),eq(template))).thenReturn(null);
        assertEquals(404,assertThrows(ApiProblem.class,()->service.complete(member,template,item,true)).status());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void completedIsAnAbsoluteValueAndNeverToggles() {
        var db=mock(OwnedDatabase.class);var service=new ChecklistService(new ChecklistRepository(db),Clock.systemUTC());var member=UUID.randomUUID();var template=UUID.randomUUID();var item=UUID.randomUUID();
        when(db.one(startsWith("select i.item_id"),eq(item),eq(template))).thenReturn(object("item_id",item));
        when(db.rows(startsWith("select i.item_id"),eq(member),eq(template))).thenReturn(List.of(object("itemId",item,"completed",true)));
        assertEquals(true,service.complete(member,template,item,true).get("completed"));
        assertEquals(true,service.complete(member,template,item,true).get("completed"));
        verify(db,times(2)).update(contains("do nothing"),eq(member),eq(item),any());
        verify(db,never()).update(startsWith("delete"),any(),any());
    }
    @Test void completionRoundingAndEmptyTemplateAreDefined() {
        assertEquals("66.7",ChecklistService.completionRate(3,2).toPlainString());assertEquals("0.0",ChecklistService.completionRate(0,0).toPlainString());
    }
}

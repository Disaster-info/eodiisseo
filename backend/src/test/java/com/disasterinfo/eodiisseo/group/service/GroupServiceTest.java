package com.disasterinfo.eodiisseo.group.service;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.group.repository.GroupRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.object;

class GroupServiceTest {
    private final Instant now=Instant.parse("2026-10-16T03:00:00Z");
    private final OwnedDatabase db=mock(OwnedDatabase.class);
    private final GroupService service=new GroupService(new GroupRepository(db),Clock.fixed(now,ZoneOffset.UTC),300);
    @Test void thirdMembershipPreventsCreatingAnotherGroup() {
        var member=UUID.randomUUID();when(db.count(contains("group_member where member_id"),eq(member))).thenReturn(3L);
        assertEquals(409,assertThrows(ApiProblem.class,()->service.create(member,"가족",null)).status());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void tenthMemberPreventsAnotherJoin() {
        var member=UUID.randomUUID();var group=UUID.randomUUID();
        when(db.one(contains("invite_code=?"),eq("A7B9K2"))).thenReturn(object("group_id",group));
        when(db.count(endsWith("where group_id=?"),eq(group))).thenReturn(10L);
        assertEquals("GROUP_FULL",assertThrows(ApiProblem.class,()->service.join(member," a7b9k2 ")).code());
    }
    @Test void groupSharingCannotOverrideGlobalConsent() {
        var member=UUID.randomUUID();var group=UUID.randomUUID();
        when(db.one(startsWith("select group_id"),eq(group))).thenReturn(object("group_id",group));
        when(db.count(contains("safety_group"),eq(group))).thenReturn(1L);
        when(db.one(startsWith("select gm.*"),eq(group),eq(member))).thenReturn(object("role","MEMBER"));
        assertEquals(403,assertThrows(ApiProblem.class,()->service.sharing(member,group,true)).status());
    }
    @Test void freshnessBoundaryAndPrivacyStatesAreDistinct() {
        assertEquals("AVAILABLE",GroupService.locationState(true,now.minusSeconds(299),now,300));
        assertEquals("STALE",GroupService.locationState(true,now.minusSeconds(300),now,300));
        assertEquals("NOT_SHARED",GroupService.locationState(false,now,now,300));
        assertEquals("NO_LOCATION",GroupService.locationState(true,null,now,300));
    }
}

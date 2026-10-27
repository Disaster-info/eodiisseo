package com.disasterinfo.eodiisseo.auth.service;
import com.disasterinfo.eodiisseo.auth.client.SocialIdentityVerifier;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.disasterinfo.eodiisseo.auth.service.*;

import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuthServiceTest {
    private final OwnedDatabase db=mock(OwnedDatabase.class);
    private final TokenService tokens=mock(TokenService.class);
    private final SocialIdentityVerifier social=mock(SocialIdentityVerifier.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final Instant now=Instant.parse("2026-10-16T03:00:00Z");
    private AuthService service() { when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());return new AuthService(new AuthRepository(db),tokens,social,Clock.fixed(now,ZoneOffset.UTC),manager,1209600); }
    @Test void refreshReplayRevocationCommitsBeforeUnauthorizedIsThrown() {
        var session=UUID.randomUUID();var member=UUID.randomUUID();var old="used-refresh";
        when(db.one(startsWith("select s.*"),eq(AuthService.hash(old)),eq(AuthService.hash(old)))).thenReturn(object("session_id",session,"member_id",member,"refresh_token_hash","new-hash","revoked_at",null,"expires_at",now.plusSeconds(600)));
        when(db.count(startsWith("select count(*) from member"),eq(member))).thenReturn(1L);
        var service=service();assertEquals(401,assertThrows(ApiProblem.class,()->service.refresh(old)).status());
        verify(db).update(startsWith("update auth_session set revoked_at"),eq(now),eq(session));
        verify(manager).commit(any());verify(manager,never()).rollback(any());
    }
    @Test void pendingLoginNeverIssuesServiceTokens() {
        var identity=new SocialIdentityVerifier.Identity("GOOGLE","subject","name");var member=UUID.randomUUID();
        when(social.verify("GOOGLE","credential")).thenReturn(identity);
        when(db.one(startsWith("select m.*"),eq("GOOGLE"),eq("subject"))).thenReturn(object("member_id",member,"status","WITHDRAWAL_PENDING","withdrawn_at",now.minusSeconds(1)));
        var result=service().login("GOOGLE","credential");
        assertEquals("WITHDRAWAL_PENDING",result.body().get("accountStatus"));assertFalse(result.body().containsKey("accessToken"));verifyNoInteractions(tokens);
    }
    @Test void administratorWithdrawalDoesNotPartiallyDeactivateTheMember() {
        var member=UUID.randomUUID();when(db.count(startsWith("select count(*) from group_member"),eq(member))).thenReturn(1L);
        assertEquals(409,assertThrows(ApiProblem.class,()->service().withdraw(new MemberPrincipal(member,UUID.randomUUID()))).status());
        verify(db,never()).update(startsWith("update member"),any(),any());verify(manager).rollback(any());
    }
    @Test void recoveryAtExactlySevenDaysIsGone() {
        when(social.verify("KAKAO","proof")).thenReturn(new SocialIdentityVerifier.Identity("KAKAO","id","name"));
        when(db.one(startsWith("select m.*"),eq("KAKAO"),eq("id"))).thenReturn(object("member_id",UUID.randomUUID(),"status","WITHDRAWAL_PENDING","withdrawn_at",now.minus(Duration.ofDays(7))));
        assertEquals(410,assertThrows(ApiProblem.class,()->service().restore("KAKAO","proof")).status());verifyNoInteractions(tokens);
    }
}

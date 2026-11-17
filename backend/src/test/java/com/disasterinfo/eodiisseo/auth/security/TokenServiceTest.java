package com.disasterinfo.eodiisseo.auth.security;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenServiceTest {
    private final Instant now=Instant.parse("2026-10-16T03:00:00Z");
    private final String secret="0123456789abcdef0123456789abcdef";
    private TokenService service(Instant instant,String issuer,String audience) { return new TokenService(secret,issuer,audience,900,Clock.fixed(instant,ZoneOffset.UTC)); }
    @Test void acceptsOnlyTheSignedMemberAndSession() {
        var member=UUID.randomUUID();var session=UUID.randomUUID();var service=service(now,"issuer","app");
        assertEquals(new MemberPrincipal(member,session),service.verify(service.issue(member,session,now.plusSeconds(1200))));
    }
    @Test void refusesTamperingWrongIssuerAndWrongAudience() {
        var service=service(now,"issuer","app");var token=service.issue(UUID.randomUUID(),UUID.randomUUID(),now.plusSeconds(1200));
        var parts=token.split("\\.");parts[1]=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"attacker\"}".getBytes());
        assertThrows(ApiProblem.class,()->service.verify(String.join(".",parts)));
        assertThrows(ApiProblem.class,()->service(now,"other","app").verify(token));
        assertThrows(ApiProblem.class,()->service(now,"issuer","other").verify(token));
    }
    @Test void accessExpiresAtSessionBoundaryEvenWhenItsDefaultTtlIsLonger() {
        var token=service(now,"issuer","app").issue(UUID.randomUUID(),UUID.randomUUID(),now.plusSeconds(30));
        assertThrows(ApiProblem.class,()->service(now.plusSeconds(30),"issuer","app").verify(token));
    }
    @Test void refusesShortSigningSecrets() { assertThrows(IllegalStateException.class,()->new TokenService("short","i","a",900,Clock.systemUTC())); }
}

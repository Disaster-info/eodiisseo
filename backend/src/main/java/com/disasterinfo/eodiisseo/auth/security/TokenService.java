package com.disasterinfo.eodiisseo.auth.security;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;

import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

public class TokenService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;
    private final String issuer,audience;
    private final long seconds;
    public TokenService(String secret,String issuer,String audience,long seconds,Clock clock) {
        if(secret==null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<32)
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        if(seconds<=0 || issuer.isBlank() || audience.isBlank()) throw new IllegalStateException("Invalid JWT settings");
        this.issuer=issuer;this.audience=audience;this.seconds=seconds;this.clock=clock;
        var key=new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256");
        encoder=NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        var nimbus=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps=new JwtTimestampValidator(Duration.ZERO);timestamps.setClock(clock);
        nimbus.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
            timestamps,new JwtIssuerValidator(issuer),new JwtClaimValidator<List<String>>("aud",a->a!=null && a.contains(audience))));
        decoder=nimbus;
    }
    public String issue(UUID member,UUID session,Instant sessionExpiry) {
        var now=clock.instant();var expiry=now.plusSeconds(seconds);
        if(expiry.isAfter(sessionExpiry)) expiry=sessionExpiry;
        var claims=JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(member.toString())
            .issuedAt(now).expiresAt(expiry).claim("sid",session.toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),claims)).getTokenValue();
    }
    public MemberPrincipal verify(String token) {
        try {
            var jwt=decoder.decode(token);
            if(jwt.getIssuedAt()==null || jwt.getIssuedAt().isAfter(clock.instant()) || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(clock.instant()))
                throw ApiProblem.unauthorized();
            return new MemberPrincipal(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")));
        } catch(RuntimeException e) { throw ApiProblem.unauthorized(); }
    }
    public long seconds() { return seconds; }
}

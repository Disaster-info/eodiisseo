package com.disasterinfo.eodiisseo.auth.client;

import java.util.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.core.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;

@Component @Profile("!test")
public class SocialIdentityVerifier {
    public record Identity(String provider,String subject,String name) {}
    private final Set<String> clients;
    private final String kakaoAppId;
    private final JwtDecoder google;
    private final RestClient kakao;
    private final Clock clock;
    public SocialIdentityVerifier(@Value("${GOOGLE_CLIENT_IDS:}") String multipleClients,
        @Value("${GOOGLE_CLIENT_ID:}") String singleClient,
        @Value("${KAKAO_APP_ID:}") String kakaoAppId,Clock clock) {
        var googleClients=multipleClients.isBlank()?singleClient:multipleClients;
        this.clock=clock;
        this.clients=new HashSet<>();for(var s:googleClients.split(",")) if(!s.isBlank()) clients.add(s.trim());
        this.kakaoAppId=kakaoAppId;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(5));
        var decoder=NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
            .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(factory)).build();
        var timestamps=new JwtTimestampValidator(Duration.ZERO);timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
            new JwtClaimValidator<String>("iss",v->Set.of("accounts.google.com","https://accounts.google.com").contains(v)),
            new JwtClaimValidator<List<String>>("aud",v->v!=null && v.stream().anyMatch(clients::contains))));
        google=decoder;kakao=RestClient.builder().requestFactory(factory).baseUrl("https://kapi.kakao.com").build();
    }
    public Identity verify(String provider,String credential) {
        if(credential==null || credential.isBlank() || credential.length()>16384) throw ApiProblem.bad("인증정보를 확인하세요.");
        try {
            if("GOOGLE".equals(provider)) {
                if(clients.isEmpty()) throw new ApiProblem(503,"SOCIAL_LOGIN_NOT_CONFIGURED","Google 설정이 필요합니다.");
                var jwt=google.decode(credential);
                if(jwt.getSubject()==null || jwt.getSubject().isBlank() || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(clock.instant())) throw ApiProblem.unauthorized();
                return new Identity(provider,jwt.getSubject(),safeName(jwt.getClaimAsString("name")));
            }
            if(!"KAKAO".equals(provider)) throw ApiProblem.bad("지원하지 않는 인증 제공자입니다.");
            if(kakaoAppId.isBlank()) throw new ApiProblem(503,"SOCIAL_LOGIN_NOT_CONFIGURED","Kakao 설정이 필요합니다.");
            var info=kakao.get().uri("/v1/user/access_token_info").header("Authorization","Bearer "+credential).retrieve().body(Map.class);
            var user=kakao.get().uri("/v2/user/me").header("Authorization","Bearer "+credential).retrieve().body(Map.class);
            if(info==null || user==null || info.get("id")==null || !Objects.equals(info.get("id"),user.get("id"))
                || !kakaoAppId.equals(String.valueOf(info.get("app_id"))) || !(info.get("expires_in") instanceof Number n) || n.longValue()<=0)
                throw ApiProblem.unauthorized();
            var properties=user.get("properties") instanceof Map<?,?> m ? m : Map.of();
            return new Identity(provider,String.valueOf(info.get("id")),safeName((String)properties.get("nickname")));
        } catch(JwtException failure) { throw ApiProblem.unauthorized(); }
        catch(HttpClientErrorException failure) {
            if(failure.getStatusCode().value()==401 || failure.getStatusCode().value()==403) throw ApiProblem.unauthorized();
            throw new ApiProblem(502,"SOCIAL_PROVIDER_UNAVAILABLE","외부 인증 응답을 확인하지 못했습니다.");
        }
        catch(RestClientException failure) { throw new ApiProblem(502,"SOCIAL_PROVIDER_UNAVAILABLE","외부 인증 서비스에 연결하지 못했습니다."); }
    }
    private String safeName(String name) {
        return name==null || name.isBlank() ? "회원" : name.strip().substring(0,Math.min(name.strip().length(),50));
    }
}

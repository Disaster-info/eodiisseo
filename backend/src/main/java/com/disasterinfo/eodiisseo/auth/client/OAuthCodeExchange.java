package com.disasterinfo.eodiisseo.auth.client;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

/** Exchanges web authorization codes; returned credentials still pass SocialIdentityVerifier. */
@Component @Profile("!test")
public class OAuthCodeExchange {
    private final Environment env;private final RestClient client;private final Set<String> redirects;
    @org.springframework.beans.factory.annotation.Autowired
    public OAuthCodeExchange(Environment env){this(env,http());}
    public OAuthCodeExchange(Environment env,RestClient client){
        this.env=env;this.client=client;
        boolean production=Arrays.stream(env.getActiveProfiles()).anyMatch(p->Set.of("prod","production").contains(p));
        String configured=env.getProperty("OAUTH_ALLOWED_REDIRECT_URIS",production?"":"http://localhost:8081,http://localhost:8081/,http://127.0.0.1:8081,http://127.0.0.1:8081/");
        redirects=new HashSet<>();for(var uri:configured.split(","))if(!uri.isBlank())redirects.add(uri.strip());
    }
    private static RestClient http(){var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(Duration.ofSeconds(2));f.setReadTimeout(Duration.ofSeconds(5));return RestClient.builder().requestFactory(f).build();}
    public String exchange(String provider,String code,String redirect,String verifier){
        if(code==null||code.isBlank()||code.length()>4096||redirect==null||redirect.length()>2048||!redirects.contains(redirect))throw ApiProblem.bad("인가 코드와 등록된 로그인 반환 주소를 확인하세요.");
        boolean google="GOOGLE".equals(provider);
        if(!google&&!"KAKAO".equals(provider))throw ApiProblem.bad("지원하지 않는 로그인 제공자입니다.");
        if(google&&(verifier==null||!verifier.matches("[A-Za-z0-9._~-]{43,128}")))throw ApiProblem.bad("Google PKCE 검증값이 필요합니다.");
        String id=google?first("GOOGLE_WEB_CLIENT_ID","GOOGLE_CLIENT_ID"):first("KAKAO_REST_API_KEY");
        if(google&&id.isBlank())id=first("GOOGLE_CLIENT_IDS").split(",")[0].strip();
        String secret=first(google?"GOOGLE_CLIENT_SECRET":"KAKAO_CLIENT_SECRET");
        if(id.isBlank()||secret.isBlank())throw new ApiProblem(503,"SOCIAL_LOGIN_NOT_CONFIGURED","웹 로그인 서버 설정이 필요합니다.");
        var form=new LinkedMultiValueMap<String,String>();form.add("grant_type","authorization_code");form.add("client_id",id);form.add("client_secret",secret);form.add("code",code);form.add("redirect_uri",redirect);if(google)form.add("code_verifier",verifier);
        Map<?,?> result;
        try{result=client.post().uri(google?"https://oauth2.googleapis.com/token":"https://kauth.kakao.com/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);}
        catch(HttpClientErrorException e){throw ApiProblem.unauthorized();}
        catch(RestClientException e){throw new ApiProblem(502,"SOCIAL_PROVIDER_UNAVAILABLE","로그인 제공자에 연결하지 못했습니다.");}
        Object token=result==null?null:result.get(google?"id_token":"access_token");
        if(!(token instanceof String value)||value.isBlank()||value.length()>16384)throw new ApiProblem(502,"SOCIAL_PROVIDER_UNAVAILABLE","로그인 제공자 응답을 확인하세요.");
        return value;
    }
    private String first(String...keys){for(var key:keys){var value=env.getProperty(key,"");if(!value.isBlank())return value.strip();}return "";}
}

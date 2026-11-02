package com.disasterinfo.eodiisseo.auth.client;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.containsString;

class OAuthCodeExchangeTest {
    MockEnvironment env;MockRestServiceServer server;OAuthCodeExchange exchange;
    @BeforeEach void start(){
        var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        env=new MockEnvironment().withProperty("GOOGLE_CLIENT_ID","test-client").withProperty("GOOGLE_CLIENT_SECRET","test-secret").withProperty("KAKAO_REST_API_KEY","test-kakao").withProperty("KAKAO_CLIENT_SECRET","test-secret");
        exchange=new OAuthCodeExchange(env,builder.build());
    }
    @Test void googleExchangesCodeWithPkceAndReturnsIdToken(){
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andExpect(content().string(containsString("code_verifier="+"a".repeat(43)))).andExpect(content().string(containsString("grant_type=authorization_code"))).andRespond(withSuccess("{\"id_token\":\"test-id-token\",\"access_token\":\"different-token\"}",MediaType.APPLICATION_JSON));
        assertEquals("test-id-token",exchange.exchange("GOOGLE","test-code","http://localhost:8081/","a".repeat(43)));server.verify();
    }
    @Test void kakaoReturnsAccessToken(){
        server.expect(requestTo("https://kauth.kakao.com/oauth/token")).andRespond(withSuccess("{\"access_token\":\"test-kakao-token\"}",MediaType.APPLICATION_JSON));
        assertEquals("test-kakao-token",exchange.exchange("KAKAO","test-code","http://localhost:8081/",null));server.verify();
    }
    @Test void unregisteredRedirectAndMissingPkceNeverReachProvider(){
        assertEquals(400,assertThrows(ApiProblem.class,()->exchange.exchange("GOOGLE","test-code","https://attacker.test/","a".repeat(43))).status());
        assertEquals(400,assertThrows(ApiProblem.class,()->exchange.exchange("GOOGLE","test-code","http://localhost:8081/",null)).status());server.verify();
    }
    @Test void expiredCodeIsUnauthorizedAndNeverExposesProviderBody(){
        server.expect(anything()).andRespond(withBadRequest().body("{\"error\":\"invalid_grant\",\"secret\":\"test-only-provider-body\"}"));
        var failure=assertThrows(ApiProblem.class,()->exchange.exchange("KAKAO","test-code","http://localhost:8081/",null));assertEquals(401,failure.status());assertFalse(failure.getMessage().contains("provider-body"));server.verify();
    }
    @Test void productionNeedsExplicitRedirectRegistration(){
        env.setActiveProfiles("production");var production=new OAuthCodeExchange(env,RestClient.create());
        assertEquals(400,assertThrows(ApiProblem.class,()->production.exchange("KAKAO","test-code","http://localhost:8081/",null)).status());
    }
}

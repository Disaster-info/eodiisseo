package com.disasterinfo.eodiisseo.auth.controller;

import com.disasterinfo.eodiisseo.auth.dto.AuthDto.*;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;

@RestController @Profile("!test")
public class AuthController {
    private final AuthService service;
    private final org.springframework.beans.factory.ObjectProvider<com.disasterinfo.eodiisseo.auth.client.OAuthCodeExchange> exchange;
    public AuthController(AuthService service,org.springframework.beans.factory.ObjectProvider<com.disasterinfo.eodiisseo.auth.client.OAuthCodeExchange> exchange) { this.service=service;this.exchange=exchange; }
    @PostMapping("/api/v1/auth/google") public ResponseEntity<?> google(@Valid @RequestBody Google b) { return login("GOOGLE",credential("GOOGLE",b.idToken(),b.code(),b.redirectUri(),b.codeVerifier())); }
    @PostMapping("/api/v1/auth/kakao") public ResponseEntity<?> kakao(@Valid @RequestBody Kakao b) { return login("KAKAO",credential("KAKAO",b.accessToken(),b.code(),b.redirectUri(),null)); }
    private String credential(String provider,String token,String code,String redirect,String verifier){
        if((token==null)==(code==null))throw com.disasterinfo.eodiisseo.auth.exception.ApiProblem.bad("제공자 토큰 또는 인가 코드 중 하나만 전달하세요.");
        if(token!=null){if(token.isBlank()||redirect!=null||verifier!=null)throw com.disasterinfo.eodiisseo.auth.exception.ApiProblem.bad("로그인 입력값을 확인하세요.");return token;}
        var client=exchange.getIfAvailable();if(client==null)throw new com.disasterinfo.eodiisseo.auth.exception.ApiProblem(503,"SOCIAL_LOGIN_NOT_CONFIGURED","웹 로그인 서버 설정이 필요합니다.");
        return client.exchange(provider,code,redirect,verifier);
    }
    private ResponseEntity<?> login(String provider,String token) { var r=service.login(provider,token);return ResponseEntity.status(r.created()?201:200).body(r.body()); }
    @PostMapping("/api/v1/auth/refresh") public Map<String,Object> refresh(@Valid @RequestBody Refresh b) { return service.refresh(b.refreshToken()); }
    @PostMapping("/api/v1/auth/social-accounts/{provider}/link") public Map<String,Object> link(@AuthenticationPrincipal MemberPrincipal p,@PathVariable String provider,@Valid @RequestBody Credential b) { return service.link(p,provider,b.socialCredential()); }
    @PostMapping("/api/v1/auth/logout") public ResponseEntity<Void> logout(@AuthenticationPrincipal MemberPrincipal p) { service.logout(p);return ResponseEntity.noContent().build(); }
    @GetMapping("/api/v1/members/me") public Map<String,Object> me(@AuthenticationPrincipal MemberPrincipal p) { return service.me(p); }
    @DeleteMapping("/api/v1/members/me") public ResponseEntity<Void> withdraw(@AuthenticationPrincipal MemberPrincipal p) { service.withdraw(p);return ResponseEntity.noContent().build(); }
    @PostMapping("/api/v1/auth/withdrawal/cancel") public Map<String,Object> restore(@Valid @RequestBody Recovery b) { return service.restore(b.provider(),b.socialCredential()); }
}

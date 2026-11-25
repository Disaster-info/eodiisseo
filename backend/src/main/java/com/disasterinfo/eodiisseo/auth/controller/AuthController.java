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
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;

@RestController @Profile("!test")
public class AuthController {
    private final AuthService service;
    private final org.springframework.beans.factory.ObjectProvider<com.disasterinfo.eodiisseo.auth.client.OAuthCodeExchange> exchange;
    public AuthController(AuthService service,org.springframework.beans.factory.ObjectProvider<com.disasterinfo.eodiisseo.auth.client.OAuthCodeExchange> exchange) { this.service=service;this.exchange=exchange; }
    // 웹(M5): X-Auth-Transport: cookie 요청이면 refresh 토큰을 본문 대신 HttpOnly 쿠키로 주고받음 (브라우저 JS 저장소에 남기지 않음)
    // 이 헤더가 있을 때만 쿠키를 읽음 → 다른 사이트의 위조 요청(CSRF)은 CORS 사전 확인에서 막힘. 앱은 헤더 없이 지금처럼 본문으로
    // 웹 주소와 백엔드 주소가 다른 사이트면 AUTH_COOKIE_SAME_SITE=None (HTTPS 필수). 로컬 http 개발은 AUTH_COOKIE_SECURE=false
    private static final String COOKIE="eodiisseo_refresh";
    @org.springframework.beans.factory.annotation.Value("${AUTH_COOKIE_SECURE:true}") private boolean cookieSecure;
    @org.springframework.beans.factory.annotation.Value("${AUTH_COOKIE_SAME_SITE:Lax}") private String cookieSameSite;
    private static boolean cookieMode(jakarta.servlet.http.HttpServletRequest r) { return "cookie".equals(r.getHeader("X-Auth-Transport")); }
    private org.springframework.http.ResponseCookie cookie(String value,long maxAge) {
        return org.springframework.http.ResponseCookie.from(COOKIE,value).httpOnly(true).secure(cookieSecure).sameSite(cookieSameSite).path("/api/v1/auth").maxAge(maxAge).build();
    }
    private ResponseEntity<?> tokens(jakarta.servlet.http.HttpServletRequest r,int status,Map<String,Object> body) {
        if(!cookieMode(r)||!(body.get("refreshToken") instanceof String refresh)) return ResponseEntity.status(status).body(body);
        var copy=new java.util.LinkedHashMap<>(body);copy.remove("refreshToken");
        long age=body.get("refreshExpiresAt") instanceof java.time.Instant until?Math.max(0,java.time.Duration.between(java.time.Instant.now(),until).getSeconds()):0;
        return ResponseEntity.status(status).header(org.springframework.http.HttpHeaders.SET_COOKIE,cookie(refresh,age).toString()).body(copy);
    }
    // 본문의 refresh 토큰, 없으면(웹 쿠키 방식) 쿠키의 값. 둘 다 없으면 null
    private static String refreshOf(jakarta.servlet.http.HttpServletRequest r,Refresh b) {
        if(b!=null) return b.refreshToken();
        if(!cookieMode(r)||r.getCookies()==null) return null;
        for(var c:r.getCookies()) if(COOKIE.equals(c.getName())&&!c.getValue().isBlank()&&c.getValue().length()<=512) return c.getValue();
        return null;
    }
    @PostMapping("/api/v1/auth/google") public ResponseEntity<?> google(jakarta.servlet.http.HttpServletRequest r,@Valid @RequestBody Google b) { return login(r,"GOOGLE",credential("GOOGLE",b.idToken(),b.code(),b.redirectUri(),b.codeVerifier())); }
    @PostMapping("/api/v1/auth/kakao") public ResponseEntity<?> kakao(jakarta.servlet.http.HttpServletRequest r,@Valid @RequestBody Kakao b) { return login(r,"KAKAO",credential("KAKAO",b.accessToken(),b.code(),b.redirectUri(),null)); }
    private String credential(String provider,String token,String code,String redirect,String verifier){
        if((token==null)==(code==null))throw com.disasterinfo.eodiisseo.auth.exception.ApiProblem.bad("제공자 토큰 또는 인가 코드 중 하나만 전달하세요.");
        if(token!=null){if(token.isBlank()||redirect!=null||verifier!=null)throw com.disasterinfo.eodiisseo.auth.exception.ApiProblem.bad("로그인 입력값을 확인하세요.");return token;}
        var client=exchange.getIfAvailable();if(client==null)throw new com.disasterinfo.eodiisseo.auth.exception.ApiProblem(503,"SOCIAL_LOGIN_NOT_CONFIGURED","웹 로그인 서버 설정이 필요합니다.");
        return client.exchange(provider,code,redirect,verifier);
    }
    private ResponseEntity<?> login(jakarta.servlet.http.HttpServletRequest req,String provider,String token) { var r=service.login(provider,token);return tokens(req,r.created()?201:200,r.body()); }
    @PostMapping("/api/v1/auth/refresh") public ResponseEntity<?> refresh(jakarta.servlet.http.HttpServletRequest r,@Valid @RequestBody(required=false) Refresh b) {
        var token=refreshOf(r,b);
        if(token==null) throw cookieMode(r)?ApiProblem.unauthorized():ApiProblem.bad("refresh 토큰이 필요합니다.");
        return tokens(r,200,service.refresh(token));
    }
    @PostMapping("/api/v1/auth/social-accounts/{provider}/link") public Map<String,Object> link(@AuthenticationPrincipal MemberPrincipal p,@PathVariable String provider,@Valid @RequestBody Credential b) { return service.link(p,provider,b.socialCredential()); }
    // access 토큰이 만료돼도 로그아웃이 서버 세션을 끊도록 refresh 토큰 본문도 받는다 (토큰·본문 둘 다 없으면 401)
    @PostMapping("/api/v1/auth/logout") public ResponseEntity<Void> logout(jakarta.servlet.http.HttpServletRequest r,@AuthenticationPrincipal MemberPrincipal p,@Valid @RequestBody(required=false) Refresh b) {
        var token=p==null?refreshOf(r,b):null;
        if(p!=null) service.logout(p); else if(token!=null) service.logoutByRefresh(token); else throw ApiProblem.unauthorized();
        var res=ResponseEntity.noContent();
        if(cookieMode(r)) res.header(org.springframework.http.HttpHeaders.SET_COOKIE,cookie("",0).toString()); // 웹: 쿠키도 지움
        return res.build();
    }
    @GetMapping("/api/v1/members/me") public Map<String,Object> me(@AuthenticationPrincipal MemberPrincipal p) { return service.me(p); }
    @DeleteMapping("/api/v1/members/me") public ResponseEntity<Void> withdraw(@AuthenticationPrincipal MemberPrincipal p) { service.withdraw(p);return ResponseEntity.noContent().build(); }
    @PostMapping("/api/v1/auth/withdrawal/cancel") public ResponseEntity<?> restore(jakarta.servlet.http.HttpServletRequest r,@Valid @RequestBody Recovery b) { return tokens(r,200,service.restore(b.provider(),b.socialCredential())); }
}

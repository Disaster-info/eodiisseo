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
    public AuthController(AuthService service) { this.service=service; }
    @PostMapping("/api/v1/auth/google") public ResponseEntity<?> google(@Valid @RequestBody Google b) { return login("GOOGLE",b.idToken()); }
    @PostMapping("/api/v1/auth/kakao") public ResponseEntity<?> kakao(@Valid @RequestBody Kakao b) { return login("KAKAO",b.accessToken()); }
    private ResponseEntity<?> login(String provider,String token) { var r=service.login(provider,token);return ResponseEntity.status(r.created()?201:200).body(r.body()); }
    @PostMapping("/api/v1/auth/refresh") public Map<String,Object> refresh(@Valid @RequestBody Refresh b) { return service.refresh(b.refreshToken()); }
    @PostMapping("/api/v1/auth/social-accounts/{provider}/link") public Map<String,Object> link(@AuthenticationPrincipal MemberPrincipal p,@PathVariable String provider,@Valid @RequestBody Credential b) { return service.link(p,provider,b.socialCredential()); }
    @PostMapping("/api/v1/auth/logout") public ResponseEntity<Void> logout(@AuthenticationPrincipal MemberPrincipal p) { service.logout(p);return ResponseEntity.noContent().build(); }
    @GetMapping("/api/v1/members/me") public Map<String,Object> me(@AuthenticationPrincipal MemberPrincipal p) { return service.me(p); }
    @DeleteMapping("/api/v1/members/me") public ResponseEntity<Void> withdraw(@AuthenticationPrincipal MemberPrincipal p) { service.withdraw(p);return ResponseEntity.noContent().build(); }
    @PostMapping("/api/v1/auth/withdrawal/cancel") public Map<String,Object> restore(@Valid @RequestBody Recovery b) { return service.restore(b.provider(),b.socialCredential()); }
}

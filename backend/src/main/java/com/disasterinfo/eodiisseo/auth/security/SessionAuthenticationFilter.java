package com.disasterinfo.eodiisseo.auth.security;
import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.io.IOException;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Verifies token signatures, current session state and server-side roles on each request. */
public final class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final AuthRepository repository;
    private final ObjectMapper json;
    public SessionAuthenticationFilter(TokenService tokens, AuthRepository repository, ObjectMapper json) {
        this.tokens=tokens; this.repository=repository; this.json=json;
    }

                protected void doFilterInternal(HttpServletRequest q,HttpServletResponse r,FilterChain chain) throws ServletException,IOException {
                    var header=q.getHeader("Authorization");
                    var path=q.getRequestURI().substring(q.getContextPath().length());
                    if(Set.of("/api/v1/auth/google","/api/v1/auth/kakao","/api/v1/auth/refresh","/api/v1/auth/withdrawal/cancel").contains(path)) header=null;
                    if(header!=null) {
                        try {
                            if(!header.startsWith("Bearer ")) throw ApiProblem.unauthorized();
                            var principal=tokens.verify(header.substring(7));
                            var session=repository.findUsableSession(principal.sessionId(),principal.memberId());
                            if(session==null) throw ApiProblem.unauthorized();
                            var roles=repository.listCurrentRoles(principal.memberId()).stream()
                                .map(v->new SimpleGrantedAuthority("ROLE_"+v.get("role"))).toList();
                            var context=SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,roles));
                            SecurityContextHolder.setContext(context);
                        } catch(ApiProblem failure) { write(json,r,401,"UNAUTHORIZED");return; }
                        catch(RuntimeException failure) { write(json,r,503,"AUTHENTICATION_UNAVAILABLE");return; }
                    }
                    try { chain.doFilter(q,r); } finally { SecurityContextHolder.clearContext(); }
                }

    private static void write(ObjectMapper json,HttpServletResponse r,int status,String code) throws IOException {
        r.setStatus(status);r.setContentType("application/json;charset=UTF-8");
        r.getWriter().write(json.writeValueAsString(Map.of("code",code,"message","요청 권한을 확인하세요.","traceId",UUID.randomUUID().toString())));
    }
}

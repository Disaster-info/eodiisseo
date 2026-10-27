package com.disasterinfo.eodiisseo.auth.config;
import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import com.disasterinfo.eodiisseo.auth.security.SessionAuthenticationFilter;
import com.disasterinfo.eodiisseo.auth.security.TokenService;

import java.io.IOException;
import java.time.Clock;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration @Profile("!test") @org.springframework.scheduling.annotation.EnableScheduling
public class AssignedSecurity {
    @Bean public Clock memberClock() { return Clock.systemUTC(); }
    @Bean public TokenService tokenService(@Value("${JWT_SECRET:}") String secret,
        @Value("${JWT_ISSUER:eodiisseo}") String issuer,@Value("${JWT_AUDIENCE:eodiisseo-mobile}") String audience,
        @Value("${JWT_ACCESS_TTL_SECONDS:900}") long seconds,Clock clock) {
        return new TokenService(secret,issuer,audience,seconds,clock);
    }
    @Bean public SecurityFilterChain assignedFeatures(HttpSecurity http,TokenService tokens,AuthRepository repository,ObjectMapper json) throws Exception {
        return http.securityMatcher("/api/v1/auth/**","/api/v1/members/me/**","/api/v1/groups/**",
                "/api/v1/weather/**","/api/v1/checklists/**","/api/v1/ops/**")
            .csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/google","/api/v1/auth/kakao",
                    "/api/v1/auth/refresh","/api/v1/auth/withdrawal/cancel").permitAll()
                .requestMatchers("/api/v1/ops/**").hasRole("OPS").anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->write(json,r,401,"UNAUTHORIZED"))
                .accessDeniedHandler((q,r,x)->write(json,r,403,"FORBIDDEN")))
            .addFilterBefore(new SessionAuthenticationFilter(tokens,repository,json),UsernamePasswordAuthenticationFilter.class).build();
    }
    private static void write(ObjectMapper json,HttpServletResponse r,int status,String code) throws IOException {
        r.setStatus(status);r.setContentType("application/json;charset=UTF-8");
        r.getWriter().write(json.writeValueAsString(Map.of("code",code,"message","요청 권한을 확인하세요.","traceId",UUID.randomUUID().toString())));
    }
}

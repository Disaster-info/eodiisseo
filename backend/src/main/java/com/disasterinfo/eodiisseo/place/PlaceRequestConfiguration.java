package com.disasterinfo.eodiisseo.place;

import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.route.service.RouteRequestLimiter;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration @Profile("!test")
public class PlaceRequestConfiguration implements WebMvcConfigurer {
    private final RouteRequestLimiter limiter;
    public PlaceRequestConfiguration(RouteRequestLimiter limiter) { this.limiter = limiter; }
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!"GET".equals(request.getMethod())) return true;
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal p))
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
                limiter.checkPlaces(p.memberId());
                return true;
            }
        }).addPathPatterns("/api/v1/places/**");
    }
}

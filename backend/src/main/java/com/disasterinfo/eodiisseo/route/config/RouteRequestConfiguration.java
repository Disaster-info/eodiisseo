package com.disasterinfo.eodiisseo.route.config;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.route.service.RouteRequestLimiter;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration @Profile("!test")
public class RouteRequestConfiguration implements WebMvcConfigurer {
    private final RouteRequestLimiter limiter;
    public RouteRequestConfiguration(RouteRequestLimiter limiter){this.limiter=limiter;}
    public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(new HandlerInterceptor(){
            public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
                if(!"GET".equals(request.getMethod()))return true;
                var auth=SecurityContextHolder.getContext().getAuthentication();
                if(auth==null||!(auth.getPrincipal() instanceof MemberPrincipal p))throw ApiProblem.unauthorized();
                limiter.check(p.memberId());return true;
            }
        }).addPathPatterns("/api/v1/routes/shelters/*");
    }
}

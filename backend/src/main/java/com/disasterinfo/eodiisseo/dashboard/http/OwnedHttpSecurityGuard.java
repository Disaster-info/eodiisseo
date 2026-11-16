package com.disasterinfo.eodiisseo.dashboard.http;

import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryAuthPort;
import com.disasterinfo.eodiisseo.auth.security.SessionAuthenticationFilter;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import com.disasterinfo.eodiisseo.notification.NotificationContracts;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.MappingMatch;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ServletRequestPathUtils;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** Refuses HTTP activation before the existing token/session filter covers every owned contract. */
@Component
@ConditionalOnProperty(name = "eodiisseo.owned-http.enabled", havingValue = "true")
public final class OwnedHttpSecurityGuard implements SmartInitializingSingleton {
    private record Route(String method, String path) {}
    private static final String DISASTER = "/api/v1/disasters/00000000-0000-0000-0000-000000000001";
    private static final String DEVICE = "/api/v1/notification-devices/audit-device";
    private static final List<Route> ROUTES = List.of(
            new Route("GET", "/api/v1/dashboard"), new Route("GET", "/api/v1/disasters"),
            new Route("GET", DISASTER), new Route("GET", DISASTER + "/ai-summary"),
            new Route("POST", "/api/v1/ai/guidance"), new Route("GET", "/api/v1/notification-settings"),
            new Route("PATCH", "/api/v1/notification-settings"), new Route("PUT", DEVICE),
            new Route("DELETE", DEVICE));

    private final ObjectProvider<FilterChainProxy> security;
    private final ListableBeanFactory beans;

    public OwnedHttpSecurityGuard(ObjectProvider<FilterChainProxy> security, ListableBeanFactory beans) {
        this.security = security;
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        FilterChainProxy proxy = security.getIfUnique();
        if (proxy == null) throw unavailable("a single existing security filter chain proxy is required");
        for (Route route : ROUTES) {
            HttpServletRequest request = request(route);
            SecurityFilterChain first = proxy.getFilterChains().stream()
                    .filter(chain -> chain.matches(request)).findFirst().orElse(null);
            if (first == null || first.getFilters().stream().noneMatch(SessionAuthenticationFilter.class::isInstance)
                    || first.getFilters().stream().noneMatch(AuthorizationFilter.class::isInstance)) {
                throw unavailable("existing bearer/session protection is missing for " + route.method() + " " + route.path());
            }
            for (var filter : first.getFilters()) {
                if (filter instanceof AuthorizationFilter authorization) {
                    var denied = authorization.getAuthorizationManager().authorize(
                            () -> new AnonymousAuthenticationToken("owned-http-startup-probe", "anonymousUser",
                                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))), request);
                    if (denied == null || denied.isGranted()) {
                        throw unavailable("anonymous access is not denied for " + route.method() + " " + route.path());
                    }
                }
            }
        }
        requireSingle(DashboardSessionVerifier.class);
        requireSingle(SummaryAuthPort.class);
        requireSingle(ChatAuthenticationPort.class);
        requireSingle(NotificationContracts.Authentication.class);
    }

    private void requireSingle(Class<?> type) {
        if (beans.getBeansOfType(type).size() != 1) {
            throw unavailable("exactly one " + type.getSimpleName() + " implementation is required");
        }
    }

    private static IllegalStateException unavailable(String reason) {
        return new IllegalStateException("Owned HTTP activation rejected: " + reason + ".");
    }

    /** Path probes contain no bearer, user identity, credentials, or application data. */
    private static HttpServletRequest request(Route route) {
        var attributes = new HashMap<String, Object>();
        HttpServletMapping mapping = new HttpServletMapping() {
            public String getMatchValue() { return ""; }
            public String getPattern() { return "/"; }
            public String getServletName() { return "dispatcherServlet"; }
            public MappingMatch getMappingMatch() { return MappingMatch.DEFAULT; }
        };
        var request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getMethod" -> route.method();
                    case "getRequestURI", "getServletPath" -> route.path();
                    case "getContextPath" -> "";
                    case "getCharacterEncoding" -> "UTF-8";
                    case "getDispatcherType" -> DispatcherType.REQUEST;
                    case "getHttpServletMapping" -> mapping;
                    case "getAttribute" -> attributes.get((String) arguments[0]);
                    case "getAttributeNames" -> Collections.enumeration(attributes.keySet());
                    case "setAttribute" -> { attributes.put((String) arguments[0], arguments[1]); yield null; }
                    case "removeAttribute" -> { attributes.remove((String) arguments[0]); yield null; }
                    case "getHeaderNames", "getHeaders" -> Collections.emptyEnumeration();
                    case "getScheme" -> "http";
                    case "getServerName" -> "localhost";
                    case "getRequestURL" -> new StringBuffer("http://localhost" + route.path());
                    case "toString" -> "Owned HTTP path probe";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> primitiveDefault(method.getReturnType());
                });
        ServletRequestPathUtils.parseAndCache(request);
        return request;
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return 0;
    }
}
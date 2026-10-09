package com.disasterinfo.eodiisseo.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 traceId(요청 추적 ID)를 만들어 붙이는 필터.
 *
 * - MDC(로그 문맥)에 "traceId"로 넣어 GlobalExceptionHandler가 오류 응답의 traceId로 사용
 * - 응답 헤더 X-Trace-Id로도 내려줘 성공 응답에서도 확인 가능
 * 명세서 공통 계약: 오류는 code/message/traceId로 통일한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 다른 필터·로그보다 먼저 ID가 정해지도록
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // 스레드 재사용 시 다른 요청에 섞이지 않도록
        }
    }
}

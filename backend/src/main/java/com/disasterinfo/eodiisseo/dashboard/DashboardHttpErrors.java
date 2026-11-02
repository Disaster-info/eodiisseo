package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.response.ErrorBody;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped error translation only; protected dashboard HTTP routes remain unregistered. */
@RestControllerAdvice(basePackages = "com.disasterinfo.eodiisseo.dashboard")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DashboardHttpErrors {

    @ExceptionHandler(DashboardException.class)
    public ResponseEntity<ErrorBody> dashboard(DashboardException failure) {
        return switch (failure.reason()) {
            case INVALID_QUERY -> common(ErrorCode.INVALID_INPUT);
            case UNAUTHENTICATED -> body(401, "UNAUTHORIZED", "인증이 필요합니다.");
            case FORBIDDEN -> body(403, "FORBIDDEN", "접근 권한이 없습니다.");
            case NOT_FOUND -> common(ErrorCode.NOT_FOUND);
            case DEPENDENCY_UNAVAILABLE, POLICY_UNRESOLVED -> common(ErrorCode.INTERNAL_ERROR);
        };
    }

    private static ResponseEntity<ErrorBody> common(ErrorCode code) {
        return body(code.getStatus().value(), code.name(), code.getMessage());
    }

    private static ResponseEntity<ErrorBody> body(int status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(code, message, MDC.get(TraceIdFilter.MDC_KEY)));
    }
}

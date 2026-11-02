package com.disasterinfo.eodiisseo.ai.http;

import com.disasterinfo.eodiisseo.ai.chat.ChatException;
import com.disasterinfo.eodiisseo.ai.summary.SummaryException;
import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.response.ErrorBody;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Domain error translation only; this advice does not register or authorize any HTTP route. */
@RestControllerAdvice(basePackages = "com.disasterinfo.eodiisseo.ai")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiExceptionHandler {

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ErrorBody> chat(ChatException failure) {
        return switch (failure.reason()) {
            case INVALID_QUESTION -> common(ErrorCode.INVALID_INPUT);
            case UNAUTHENTICATED -> unauthorized();
            case DEPENDENCY_UNAVAILABLE, POLICY_UNRESOLVED, PERSISTENCE_FAILURE -> common(ErrorCode.INTERNAL_ERROR);
        };
    }

    @ExceptionHandler(SummaryException.class)
    public ResponseEntity<ErrorBody> summary(SummaryException failure) {
        return switch (failure.reason()) {
            case INVALID_DISASTER_ID -> common(ErrorCode.INVALID_INPUT);
            case UNAUTHENTICATED -> unauthorized();
            case NOT_FOUND -> common(ErrorCode.NOT_FOUND);
            case DEPENDENCY_UNAVAILABLE -> common(ErrorCode.INTERNAL_ERROR);
        };
    }

    private static ResponseEntity<ErrorBody> common(ErrorCode code) {
        return ResponseEntity.status(code.getStatus())
                .body(new ErrorBody(code.name(), code.getMessage(), MDC.get(TraceIdFilter.MDC_KEY)));
    }

    private static ResponseEntity<ErrorBody> unauthorized() {
        return ResponseEntity.status(401)
                .body(new ErrorBody("UNAUTHORIZED", "인증이 필요합니다.", MDC.get(TraceIdFilter.MDC_KEY)));
    }
}

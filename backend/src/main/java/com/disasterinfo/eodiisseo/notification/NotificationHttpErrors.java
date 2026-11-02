package com.disasterinfo.eodiisseo.notification;

import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.response.ErrorBody;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Preserves notification errors before the shared advice's generic exception handler. */
@RestControllerAdvice(basePackages = "com.disasterinfo.eodiisseo.notification")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationHttpErrors {
    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<ErrorBody> notification(NotificationException failure) {
        return ResponseEntity.status(failure.status()).body(new ErrorBody(
                failure.code(), failure.getMessage(), MDC.get(TraceIdFilter.MDC_KEY)));
    }
}

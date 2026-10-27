package com.disasterinfo.eodiisseo.auth.exception;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice(basePackages = {"com.disasterinfo.eodiisseo.auth","com.disasterinfo.eodiisseo.group",
    "com.disasterinfo.eodiisseo.weather","com.disasterinfo.eodiisseo.checklist","com.disasterinfo.eodiisseo.ops"})
public class ApiErrors {
    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String,Object>> problem(ApiProblem e) {
        if(e.status()==429) return ResponseEntity.status(429).header("Retry-After","60").body(Map.of("code",e.code(),"message",e.getMessage(),"traceId",UUID.randomUUID().toString()));
        return response(e.status(), e.code(), e.getMessage());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
        jakarta.validation.ConstraintViolationException.class})
    public ResponseEntity<Map<String,Object>> invalid(Exception ignored) {
        return response(400, "INVALID_REQUEST", "입력값을 확인하세요.");
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String,Object>> forbidden(Exception ignored) {
        return response(403, "FORBIDDEN", "접근 권한이 없습니다.");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> internal(Exception ignored) {
        return response(500, "INTERNAL_ERROR", "요청을 처리하지 못했습니다.");
    }
    private ResponseEntity<Map<String,Object>> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message,
            "traceId", UUID.randomUUID().toString()));
    }
}

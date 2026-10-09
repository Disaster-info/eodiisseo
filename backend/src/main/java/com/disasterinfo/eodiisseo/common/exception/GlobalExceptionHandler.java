package com.disasterinfo.eodiisseo.common.exception;

import com.disasterinfo.eodiisseo.common.config.TraceIdFilter;
import com.disasterinfo.eodiisseo.common.response.ErrorBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 모든 Controller에서 발생한 예외를 한곳에서 받아 명세서 공통 오류 형식 {code, message, traceId}로 바꾸는 전역 처리기.
 *
 * 처리 순서 (구체적인 예외 타입의 핸들러가 먼저 선택된다):
 * 1. BusinessException                 → ErrorCode에 정의된 상태코드 (예: SHELTER_NOT_FOUND 404, ROUTE_NOT_FOUND 502)
 * 2. BindException                     → 400 INVALID_INPUT, 어떤 필드가 왜 틀렸는지 메시지에 포함
 * 3. MethodArgumentTypeMismatchException → 400 INVALID_INPUT (예: shelterId가 UUID 형식이 아님)
 * 4. 그 밖의 모든 예외                  → Spring MVC 4xx는 상태 유지, 나머지는 500 INTERNAL_ERROR
 *
 * 덕분에 Controller·Service에는 try-catch 없이 정상 흐름만 작성하면 된다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 의도적으로 던진 예외. 5xx(외부 API 장애 등)만 로그를 남기고, 4xx는 정상적인 사용자 오류라 남기지 않는다
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorBody> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        if (code.getStatus().is5xxServerError()) {
            log.warn("{}: {}", code, e.getMessage(), e.getCause());
        }
        return body(code.getStatus(), code, e.getMessage());
    }

    // @Valid 검증 실패 (@RequestBody는 MethodArgumentNotValidException, @ModelAttribute는 BindException — 전자가 후자를 상속)
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorBody> handleValidation(BindException e) {
        FieldError field = e.getFieldError();
        String message = field == null
                ? ErrorCode.INVALID_INPUT.getMessage()
                : field.getField() + ": " + field.getDefaultMessage();
        return body(ErrorCode.INVALID_INPUT.getStatus(), ErrorCode.INVALID_INPUT, message);
    }

    // 경로 변수·쿼리 값의 타입 변환 실패 (예: /api/v1/shelters/abc → UUID 아님)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return body(ErrorCode.INVALID_INPUT.getStatus(), ErrorCode.INVALID_INPUT, e.getName() + ": 형식이 올바르지 않습니다.");
    }

    // 위에서 처리하지 않은 나머지 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception e) {
        // 없는 경로(404)·메서드 불일치(405)·필수 파라미터 누락 등 Spring MVC가 던지는 예외는 ErrorResponse를 구현하므로
        // 원래 상태코드를 유지한다. 이걸 빼면 없는 URL 요청까지 500으로 응답하게 된다.
        if (e instanceof ErrorResponse mvc && mvc.getStatusCode().is4xxClientError()) {
            ErrorCode code = mvc.getStatusCode().value() == 404 ? ErrorCode.NOT_FOUND : ErrorCode.INVALID_INPUT;
            return body(mvc.getStatusCode(), code, code.getMessage());
        }
        // 진짜 버그. 원인은 로그로만 남기고 클라이언트에는 내부 정보를 노출하지 않는다
        log.error("Unhandled exception", e);
        return body(ErrorCode.INTERNAL_ERROR.getStatus(), ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage());
    }

    // traceId는 TraceIdFilter가 요청 시작 시 MDC에 넣어 둔 값
    private static ResponseEntity<ErrorBody> body(HttpStatusCode status, ErrorCode code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code.name(), message, MDC.get(TraceIdFilter.MDC_KEY)));
    }
}

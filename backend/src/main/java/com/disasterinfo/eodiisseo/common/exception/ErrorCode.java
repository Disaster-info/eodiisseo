package com.disasterinfo.eodiisseo.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트에 노출하는 오류 코드 목록. 코드마다 HTTP 상태와 기본 메시지를 함께 정의한다.
 * 상태코드는 API 명세서 5.2.1의 각 API "오류 코드"·"예외 처리" 항목을 따른다.
 *
 * 사용법: Service·Client에서 {@code throw new BusinessException(ErrorCode.SHELTER_NOT_FOUND)}
 * → GlobalExceptionHandler가 여기 정의된 HTTP 상태와 {code, message, traceId} 본문으로 응답한다.
 *
 * 기능별 코드는 접두어(ROUTE_, SHELTER_ …)를 붙여 아래 "기능별" 영역에 추가한다.
 */
public enum ErrorCode {

    // ── 공통 ──
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),            // 검증 실패, 파라미터 누락·형식 오류
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 자원을 찾을 수 없습니다."),                // 없는 URL 등
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."), // 예상하지 못한 예외

    // ── 외부 API (TMAP, 공공데이터) — 명세: 제공자 인증·quota·결과코드·파싱 실패는 502, 요청 예산 초과는 504 ──
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "외부 서비스 호출에 실패했습니다."),
    EXTERNAL_API_NOT_CONFIGURED(HttpStatus.BAD_GATEWAY, "외부 서비스 인증키가 설정되지 않았습니다."), // 제공자 인증 실패와 같은 범주
    EXTERNAL_API_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "외부 서비스 응답 시간이 초과되었습니다."),

    // ── 기능별 ──
    SHELTER_NOT_FOUND(HttpStatus.NOT_FOUND, "대피소를 찾을 수 없습니다."),            // API-SHELTER-002, API-ROUTE-001
    ROUTE_NOT_FOUND(HttpStatus.BAD_GATEWAY, "경로를 찾을 수 없습니다.");               // API-ROUTE-001: 유효 경로선 없음은 502

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}

package com.disasterinfo.eodiisseo.common.response;

/**
 * 오류 응답 본문 — API 명세서 5.2.1 "공통 타입과 상태 계약"의 형식.
 *
 * <pre>
 * {"code": "SHELTER_NOT_FOUND", "message": "대피소를 찾을 수 없습니다.", "traceId": "3f2c…"}
 * </pre>
 *
 * 성공 응답은 감싸지 않고 각 API의 응답 DTO를 그대로 반환한다 (명세: "성공 응답은 기존 최상위 키를 유지").
 * 이 클래스는 GlobalExceptionHandler만 사용한다.
 *
 * @param code    ErrorCode 이름. 앱은 이 값으로 오류 종류를 구분한다
 * @param message 사용자에게 보여줄 수 있는 설명
 * @param traceId 요청 추적 ID. 같은 값이 응답 헤더 X-Trace-Id와 서버 로그에도 남아 문의 시 로그를 찾을 수 있다
 */
public record ErrorBody(String code, String message, String traceId) {
}

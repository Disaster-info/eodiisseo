package com.disasterinfo.eodiisseo.common.exception;

import java.net.SocketTimeoutException;
import org.springframework.web.client.RestClientException;

/**
 * 외부 API 호출 실패(RestClientException)를 명세서의 상태코드로 나누는 도구.
 *
 * 명세 "TMAP 경로 및 검색 운영 기준": 제공자 인증·quota·결과코드·파싱 실패 → 502, 요청 예산(시간) 초과 → 504
 * - 연결·응답 대기 시간 초과 → EXTERNAL_API_TIMEOUT (504)
 * - 그 밖의 실패(HTTP 오류 응답, 연결 거부, JSON 파싱 실패 등) → EXTERNAL_API_ERROR (502)
 */
public final class ExternalApiErrors {

    private ExternalApiErrors() {
    }

    public static BusinessException from(RestClientException e) {
        return new BusinessException(isTimeout(e) ? ErrorCode.EXTERNAL_API_TIMEOUT : ErrorCode.EXTERNAL_API_ERROR, e);
    }

    // 원인 예외를 따라 내려가며 타임아웃이 있는지 확인 (RestClient는 ResourceAccessException으로 감싸서 던진다)
    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}

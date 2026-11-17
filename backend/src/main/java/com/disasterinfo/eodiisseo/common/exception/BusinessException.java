package com.disasterinfo.eodiisseo.common.exception;

/**
 * Service·Client 계층에서 "예상 가능한 실패"를 알릴 때 던지는 예외.
 *
 * Controller에서 try-catch로 직접 처리하지 않는다. 던지기만 하면 GlobalExceptionHandler가 잡아서
 * ErrorCode의 HTTP 상태 + {code, message, traceId} 형식으로 응답한다.
 *
 * <pre>
 * if (대피소 없음) throw new BusinessException(ErrorCode.SHELTER_NOT_FOUND);
 * catch (외부 호출 실패 e) { throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR, e); } // 원인 예외는 로그용으로 보존
 * if (검증 실패) throw new BusinessException(ErrorCode.INVALID_INPUT, "latitude와 longitude는 함께 보내야 합니다.");
 * </pre>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 상황별 메시지를 직접 지정 (응답 message에 그대로 나감) */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

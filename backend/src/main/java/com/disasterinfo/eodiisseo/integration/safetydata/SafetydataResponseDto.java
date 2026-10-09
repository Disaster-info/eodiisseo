package com.disasterinfo.eodiisseo.integration.safetydata;

import java.util.List;

/**
 * 재난안전데이터 공유플랫폼 V2 API의 공통 응답 형식. API마다 body 안의 항목 타입(T)만 다르다.
 *
 * <pre>
 * {"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE", "errorMsg": null},
 *  "numOfRows": 1000, "pageNo": 1, "totalCount": 236,
 *  "body": [ {...}, {...} ]}           ← 결과가 없으면 body는 null
 * </pre>
 *
 * 주의: 키 오류 등 실패도 HTTP 200으로 오고 header.resultCode로만 구분된다 (2026-10-09 실호출 확인).
 * 예) 미등록 키 → resultCode "30", errorMsg "등록되지 않은 서비스키"
 */
public record SafetydataResponseDto<T>(
        Header header,
        Integer numOfRows,
        Integer pageNo,
        Integer totalCount,
        List<T> body
) {

    /** resultCode "00"만 정상. 01·02 일시 오류, 10~13 요청 오류, 20·22 접근 거부·한도, 30~34 키 오류 */
    public record Header(String resultCode, String resultMsg, String errorMsg) {

        public boolean isOk() {
            return "00".equals(resultCode);
        }
    }

    /** body가 null(결과 없음)이어도 빈 목록으로 다루기 위함 */
    public List<T> items() {
        return body == null ? List.of() : body;
    }
}

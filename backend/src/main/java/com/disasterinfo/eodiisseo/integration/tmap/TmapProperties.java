package com.disasterinfo.eodiisseo.integration.tmap;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 external.tmap 설정을 담는 객체. Spring이 시작할 때 값을 채워 Bean으로 등록한다.
 *
 * <pre>
 * external:
 *   tmap:
 *     base-url: https://apis.openapi.sk.com   → baseUrl
 *     app-key: ${TMAP_APP_KEY:}               → appKey (OS 환경변수 또는 루트 .env 에서 읽음)
 *     connect-timeout: 3s                     → connectTimeout (TMAP 서버 연결 대기 한도)
 *     read-timeout: 5s                        → readTimeout   (응답 대기 한도)
 * </pre>
 *
 * 키를 코드에 직접 쓰지 않기 위해 이 객체를 거쳐 TmapClient에 전달한다.
 */
@ConfigurationProperties(prefix = "external.tmap")
public record TmapProperties(String baseUrl, String appKey, Duration connectTimeout, Duration readTimeout) {

    /** 키가 비어 있으면 TMAP을 호출하지 않고 503(EXTERNAL_API_NOT_CONFIGURED)으로 응답하기 위한 확인용 */
    public boolean hasAppKey() {
        return appKey != null && !appKey.isBlank();
    }
}

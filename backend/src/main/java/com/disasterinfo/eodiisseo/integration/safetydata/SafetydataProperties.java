package com.disasterinfo.eodiisseo.integration.safetydata;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 external.safetydata 설정 (재난안전데이터 공유플랫폼).
 *
 * 공유플랫폼은 공공데이터포털과 달리 API마다 서비스키가 따로 발급된다.
 * 그래서 키를 하나로 두지 않고 API별 필드로 둔다. 새 API를 붙일 때 필드를 추가한다.
 *
 * <pre>
 * earthquake-shelter-key ← SAFETYDATA_EARTHQUAKE_SHELTER_KEY (SD-001 지진옥외대피장소)
 * </pre>
 */
@ConfigurationProperties(prefix = "external.safetydata")
public record SafetydataProperties(
        String baseUrl,
        String earthquakeShelterKey,
        Duration connectTimeout,
        Duration readTimeout
) {
}

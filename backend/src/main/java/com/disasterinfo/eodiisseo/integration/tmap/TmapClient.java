package com.disasterinfo.eodiisseo.integration.tmap;

import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.exception.ExternalApiErrors;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * TMAP REST API 호출 전담 클래스 (integration 계층).
 *
 * 역할 분리:
 * - 이 클래스: HTTP 호출, 인증 헤더, 타임아웃, 외부 오류 → BusinessException 변환까지만
 * - RouteService: 명세 응답 형식(ShelterRouteResponse)으로의 변환
 * 외부 규격(TmapPedestrianRequest/TmapResponseDto)만 다루므로 TMAP 규격이 바뀌면 이 패키지만 고치면 된다.
 */
@Component
public class TmapClient {

    private static final Logger log = LoggerFactory.getLogger(TmapClient.class);

    private final TmapProperties properties;
    private final RestClient restClient;

    // RestClient: Spring 6.1+의 동기 HTTP 클라이언트. baseUrl·타임아웃을 한 번 설정해 두고 재사용한다
    public TmapClient(TmapProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 보행자 경로안내 호출.
     * POST https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1  (헤더 appKey, 본문 JSON)
     *
     * @throws BusinessException 키 미설정·TMAP 오류 응답·네트워크 오류(502) / 시간 초과(504). 자동 재시도는 하지 않는다(명세: TMAP_ROUTE_MAX_RETRIES=0)
     */
    public TmapResponseDto pedestrianRoute(TmapPedestrianRequest request) {
        // 키가 없으면 TMAP에 보내봐야 401이므로 미리 막고 원인을 분명히 알린다
        if (!properties.hasAppKey()) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_NOT_CONFIGURED);
        }
        // TMAP 규격상 이름은 URL 인코딩해서 보내야 하므로 이름만 바꾼 사본을 만든다
        TmapPedestrianRequest body = new TmapPedestrianRequest(
                request.startX(), request.startY(), request.endX(), request.endY(),
                encode(request.startName()), encode(request.endName()),
                request.reqCoordType(), request.resCoordType(), request.searchOption());
        try {
            return restClient.post()
                    .uri("/tmap/routes/pedestrian?version=1")
                    .header("appKey", properties.appKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TmapResponseDto.class);
        } catch (RestClientResponseException e) {
            // TMAP이 4xx/5xx로 응답한 경우 (키 오류 401, 한도 초과 등)
            // 요청 URL·appKey·좌표는 로그에 남기지 않는다 (외부 API 정리본 TM-001 주의사항)
            log.warn("TMAP pedestrian route failed: status={}", e.getStatusCode().value());
            throw ExternalApiErrors.from(e);
        } catch (RestClientException e) {
            // 응답 자체를 못 받은 경우 (타임아웃, DNS·네트워크 오류)
            log.warn("TMAP pedestrian route failed: {}", e.getClass().getSimpleName());
            throw ExternalApiErrors.from(e); // 시간 초과면 504, 나머지는 502
        }
    }

    // "역삼초 체육관" → "%EC%97%AD..." (UTF-8 URL 인코딩)
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

package com.disasterinfo.eodiisseo.integration.safetydata;

import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
import com.disasterinfo.eodiisseo.common.exception.ExternalApiErrors;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 재난안전데이터 공유플랫폼 API 호출 전담 클래스 (integration 계층).
 *
 * 역할: HTTP 호출, 페이지 넘기기, 응답 헤더(resultCode) 확인, 오류 → BusinessException 변환까지.
 * 거리 계산·반경 필터 같은 도메인 로직은 ShelterService가 한다.
 */
@Component
public class SafetydataClient {

    private static final Logger log = LoggerFactory.getLogger(SafetydataClient.class);

    private static final String EARTHQUAKE_SHELTER_PATH = "/V2/api/DSSP-IF-10943"; // SD-001
    private static final int PAGE_SIZE = 1000; // 실호출 확인: 서울 강남 반경 5km ≈ 236건이 한 페이지에 들어옴
    private static final int MAX_PAGES = 5;    // 범위를 잘못 넓게 잡았을 때 무한히 받지 않도록 하는 안전장치

    private static final ParameterizedTypeReference<SafetydataResponseDto<EarthquakeShelterItem>> EARTHQUAKE_SHELTER_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final SafetydataProperties properties;
    private final RestClient restClient;

    public SafetydataClient(SafetydataProperties properties) {
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
     * SD-001 지진옥외대피장소를 좌표 범위(사각형)로 조회한다.
     * GET https://www.safetydata.go.kr/V2/api/DSSP-IF-10943?serviceKey=..&returnType=json&startLot=..&endLot=..&startLat=..&endLat=..
     *
     * @return 범위 안의 모든 대피장소 (여러 페이지면 이어 붙임). 결과가 없으면 빈 목록
     * @throws BusinessException 키 미설정·공유플랫폼 오류 응답(502) / 시간 초과(504)
     */
    public List<EarthquakeShelterItem> earthquakeShelters(double minLat, double maxLat, double minLng, double maxLng) {
        String key = properties.earthquakeShelterKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_API_NOT_CONFIGURED);
        }
        List<EarthquakeShelterItem> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            SafetydataResponseDto<EarthquakeShelterItem> res = get(EARTHQUAKE_SHELTER_PATH, key, page, minLat, maxLat, minLng, maxLng);
            all.addAll(res.items());
            // 전체 건수만큼 다 받았거나, 이번 페이지가 덜 찼으면 마지막 페이지
            int total = res.totalCount() == null ? 0 : res.totalCount();
            if (all.size() >= total || res.items().size() < PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    private SafetydataResponseDto<EarthquakeShelterItem> get(String path, String key, int page,
                                                            double minLat, double maxLat, double minLng, double maxLng) {
        SafetydataResponseDto<EarthquakeShelterItem> res;
        try {
            res = restClient.get()
                    .uri(b -> b.path(path)
                            .queryParam("serviceKey", key)
                            .queryParam("returnType", "json")
                            .queryParam("pageNo", page)
                            .queryParam("numOfRows", PAGE_SIZE)
                            .queryParam("startLot", minLng) // Lot = 경도
                            .queryParam("endLot", maxLng)
                            .queryParam("startLat", minLat) // Lat = 위도
                            .queryParam("endLat", maxLat)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(EARTHQUAKE_SHELTER_TYPE);
        } catch (RestClientException e) {
            // HTTP 오류·타임아웃·JSON이 아닌 응답(게이트웨이 오류는 XML일 수 있음). 키·URL은 로그에 남기지 않는다
            log.warn("Safetydata {} failed: {}", path, e.getClass().getSimpleName());
            throw ExternalApiErrors.from(e); // 시간 초과면 504, 나머지는 502
        }
        // 공유플랫폼은 실패도 HTTP 200으로 보내므로 header.resultCode를 직접 확인해야 한다
        if (res == null || res.header() == null || !res.header().isOk()) {
            String code = res == null || res.header() == null ? "null" : res.header().resultCode();
            String msg = res == null || res.header() == null ? "" : res.header().resultMsg();
            log.warn("Safetydata {} error: resultCode={} {}", path, code, msg);
            throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
        }
        return res;
    }
}

package com.disasterinfo.eodiisseo.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 공통 설정. 현재는 CORS만 설정한다.
 *
 * 왜 필요한가: 브라우저는 다른 출처(origin = 프로토콜+호스트+포트)로의 요청을 기본적으로 막는다.
 * Expo web은 http://localhost:8081, API는 http://localhost:8080 으로 포트가 달라 "다른 출처"가 되므로,
 * 서버가 Access-Control-Allow-Origin 헤더로 허용해 줘야 브라우저가 응답을 읽을 수 있다.
 *
 * 네이티브 앱(Expo Go)은 브라우저가 아니라서 CORS 대상이 아니다.
 * 허용 목록은 application.yml의 app.cors.allowed-origin-patterns (기본: localhost·192.168.x.x),
 * 배포 시 환경변수 CORS_ALLOWED_ORIGIN_PATTERNS로 실제 도메인만 허용하도록 바꾼다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;

    // yml의 쉼표 구분 문자열이 배열로 자동 변환되어 주입된다
    public WebConfig(@Value("${app.cors.allowed-origin-patterns}") String[] allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    // /api/** 경로에만 적용. 목록에 없는 출처의 사전요청(OPTIONS)은 403으로 거부된다
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .exposedHeaders(TraceIdFilter.HEADER); // 브라우저 JS가 응답의 X-Trace-Id를 읽을 수 있도록
    }
}

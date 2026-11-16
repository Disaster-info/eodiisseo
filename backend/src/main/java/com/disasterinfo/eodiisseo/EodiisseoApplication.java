package com.disasterinfo.eodiisseo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
// @ConfigurationProperties가 붙은 설정 클래스(예: TmapProperties)를 찾아 application.yml 값을 채워 Bean으로 등록
@ConfigurationPropertiesScan
public class EodiisseoApplication {

    public static void main(String[] args) {
        SpringApplication.run(EodiisseoApplication.class, args);
    }
}

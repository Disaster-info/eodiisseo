package com.disasterinfo.eodiisseo.weather.client;

import com.disasterinfo.eodiisseo.weather.util.KmaGrid;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EODIISSEO_LIVE_WEATHER_TESTS",matches="true")
class KmaLiveIntegrationTest {
    @Test void officialObservationIsFreshAndContainsNoAuthenticationMaterial(){
        // Spring HTTP debug logging can include query strings. Never log a real serviceKey URI.
        Logger web=(Logger)LoggerFactory.getLogger("org.springframework.web");Level previous=web.getLevel();web.setLevel(Level.OFF);
        try {
            String key=System.getenv("KMA_SERVICE_KEY");if(key==null||key.isBlank())key=System.getenv("DATA_GO_KR_SERVICE_KEY");
            var provider=new KmaWeatherProvider(key,"https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0");
            Instant now=Instant.now();var value=provider.current(KmaGrid.from(37.5665,126.978),now);
            assertNotNull(value.temperature());assertFalse(value.observedAt().isAfter(now));
            assertTrue(value.observedAt().plusSeconds(10800).isAfter(now));
            assertFalse(value.sourcePayload().containsKey("serviceKey"));
        } finally {web.setLevel(previous);}
    }
}

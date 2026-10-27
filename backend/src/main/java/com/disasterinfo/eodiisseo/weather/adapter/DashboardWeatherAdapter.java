package com.disasterinfo.eodiisseo.weather.adapter;

import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.Weather;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardWeatherReader;
import com.disasterinfo.eodiisseo.weather.service.WeatherService;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Weather retains ownership of cache, freshness and upstream failure policy. */
@Component @Profile("!test")
@ConditionalOnProperty(name="AUTH_BRIDGES_ENABLED",havingValue="true")
public class DashboardWeatherAdapter implements DashboardWeatherReader {
    private final WeatherService weather;
    public DashboardWeatherAdapter(WeatherService weather){this.weather=weather;}
    @Override public Weather findWeather(BaseLocation location){
        if(location==null)return null;
        var result=weather.currentCoordinates(location.latitude(),location.longitude());
        return new Weather((BigDecimal)result.get("temperature"),(String)result.get("weatherCode"),
            (String)result.get("weatherText"),(Instant)result.get("observedAt"),(Instant)result.get("collectedAt"),
            (String)result.get("conditionBasis"),(Instant)result.get("skyValidAt"),Boolean.TRUE.equals(result.get("stale")),(String)result.get("guidance"));
    }
}

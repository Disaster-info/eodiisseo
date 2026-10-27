package com.disasterinfo.eodiisseo.weather.service;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.weather.client.WeatherProvider;
import com.disasterinfo.eodiisseo.weather.dto.WeatherObservation;
import com.disasterinfo.eodiisseo.weather.util.KmaGrid;

import com.disasterinfo.eodiisseo.weather.repository.WeatherRepository;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.*;
import tools.jackson.databind.ObjectMapper;

@Service @Profile("!test")
public class WeatherService {
    private final WeatherRepository repository;private final WeatherProvider provider;private final Clock clock;private final ObjectMapper json;
    private final long cacheSeconds,staleSeconds,fallbackSeconds;private final String sourceKey;
    public WeatherService(WeatherRepository repository,WeatherProvider provider,Clock clock,ObjectMapper json,
        @Value("${WEATHER_CACHE_TTL_SECONDS:600}") long cache,@Value("${WEATHER_STALE_AFTER_SECONDS:7200}") long stale,
        @Value("${WEATHER_FALLBACK_MAX_AGE_SECONDS:10800}") long fallback,@Value("${WEATHER_SOURCE_ENDPOINT_KEY:WEATHER_API}") String sourceKey) {
        this.repository=repository;this.provider=provider;this.clock=clock;this.json=json;cacheSeconds=cache;staleSeconds=stale;fallbackSeconds=fallback;this.sourceKey=sourceKey;
        if(cache<=0 || stale<=cache || fallback<=stale) throw new IllegalStateException("Invalid weather cache/age settings");
    }
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> current(UUID member,Double latitude,Double longitude,UUID interestArea) {
        validateInput(latitude,longitude,interestArea);
        String name=null;
        if(interestArea!=null) {
            if(!repository.tableExists("interest_area") || !repository.tableExists("admin_area")) throw new ApiProblem(503,"AREA_DEPENDENCY_NOT_READY","관심지역 스키마 연결이 필요합니다.");
            var area=repository.findInterestArea(interestArea);
            if(area==null) throw ApiProblem.missing("INTEREST_AREA_NOT_FOUND");if(!member.equals(area.get("member_id"))) throw ApiProblem.forbidden();
            if(area.get("latitude")==null || area.get("longitude")==null) throw ApiProblem.missing("AREA_LOCATION_NOT_FOUND");
            latitude=((Number)area.get("latitude")).doubleValue();longitude=((Number)area.get("longitude")).doubleValue();name=(String)area.get("display_name");
        }
        var grid=KmaGrid.from(latitude,longitude);
        if(grid.x()<1 || grid.x()>149 || grid.y()<1 || grid.y()>253) throw ApiProblem.missing("WEATHER_REGION_UNSUPPORTED");
        if(!repository.tableExists("data_source")) throw new ApiProblem(503,"WEATHER_SOURCE_NOT_READY","기상청 제공처 등록이 필요합니다.");
        var source=repository.findActiveWeatherSource(sourceKey);
        if(source==null) throw new ApiProblem(503,"WEATHER_SOURCE_NOT_READY","기상청 제공처 등록이 필요합니다.");
        var id=(UUID)source.get("source_id");
        repository.lockGrid(id+":"+grid.x()+":"+grid.y());
        var now=clock.instant();var previous=latest(id,grid);WeatherObservation observation;
        boolean failed=false;
        if(previous!=null && previous.collectedAt().plusSeconds(cacheSeconds).isAfter(now) && previous.observedAt().plusSeconds(staleSeconds).isAfter(now)
            && (!"FORECAST".equals(previous.basis()) || previous.skyValidAt()!=null && previous.skyValidAt().plusSeconds(3600).isAfter(now))) observation=previous;
        else {
            try {
                observation=provider.current(grid,now);
                if(observation.observedAt().isAfter(now) || !observation.observedAt().plusSeconds(fallbackSeconds).isAfter(now)) throw new ApiProblem(502,"WEATHER_DATA_UNAVAILABLE","유효한 실황 자료가 없습니다.");
                repository.saveObservation(
                    UUID.randomUUID(),id,observation.temperature(),observation.code(),observation.observedAt(),observation.collectedAt(),grid.x(),grid.y(),observation.basis(),observation.skyValidAt(),json.writeValueAsString(observation.sourcePayload()));
            } catch(ApiProblem failure) {
                if(previous==null || !previous.observedAt().plusSeconds(fallbackSeconds).isAfter(now)) throw failure;
                observation=previous;failed=true;
            }
        }
        return object("location",object("name",name,"latitude",latitude,"longitude",longitude),"temperature",observation.temperature(),
            "weatherCode",observation.code(),"weatherText",weatherText(observation.code()),"observedAt",observation.observedAt(),"collectedAt",observation.collectedAt(),
            "conditionBasis",observation.basis(),"skyValidAt",observation.skyValidAt(),"stale",failed||!observation.observedAt().plusSeconds(staleSeconds).isAfter(now),"guidance",guidance(observation.code()));
    }
    private WeatherObservation latest(UUID source,KmaGrid grid) {
        var row=repository.findLatestObservation(source,grid.x(),grid.y());
        return row==null?null:new WeatherObservation((BigDecimal)row.get("temperature_c"),(String)row.get("condition_code"),(String)row.get("condition_basis"),instant(row.get("observed_at")),instant(row.get("collected_at")),instant(row.get("sky_valid_at")),Map.of());
    }
    public static void validateInput(Double latitude,Double longitude,UUID interestArea) {
        boolean coordinate=latitude!=null || longitude!=null;
        if(coordinate==(interestArea!=null) || coordinate && (latitude==null || longitude==null)) throw ApiProblem.bad("좌표 한 쌍 또는 관심지역 중 하나가 필요합니다.");
        if(coordinate) KmaGrid.from(latitude,longitude);
    }
    private String weatherText(String code) { return switch(code) {case "SUNNY"->"맑음";case "CLOUDY"->"구름 많음";case "OVERCAST"->"흐림";case "RAIN"->"비";case "SNOW"->"눈";case "SLEET"->"비 또는 눈";default->"상태 미확인";}; }
    private String guidance(String code) { return switch(code) {case "RAIN","SLEET"->"외출 시 우산을 준비하세요.";case "SNOW"->"미끄러운 길에 주의하세요.";case "UNKNOWN"->"최신 기상청 안내를 확인하세요.";default->"공식 기상정보와 현장 상황을 함께 확인하세요.";}; }
}

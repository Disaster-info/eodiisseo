package com.disasterinfo.eodiisseo.weather.client;
import com.disasterinfo.eodiisseo.weather.dto.WeatherObservation;
import com.disasterinfo.eodiisseo.weather.util.KmaGrid;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;

@Component @Profile("!test")
public class KmaWeatherProvider implements WeatherProvider {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final RestClient client;private final String key;private final String baseUrl;
    @org.springframework.beans.factory.annotation.Autowired
    public KmaWeatherProvider(@Value("${KMA_SERVICE_KEY:}") String overrideKey,
        @Value("${DATA_GO_KR_SERVICE_KEY:}") String fallbackKey,
        @Value("${KMA_WEATHER_BASE_URL:https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0}") String baseUrl) {
        this(overrideKey.isBlank()?fallbackKey:overrideKey,baseUrl);
    }
    public KmaWeatherProvider(String key,String baseUrl) {
        this.key=key.matches(".*%[0-9a-fA-F]{2}.*")?URLDecoder.decode(key,StandardCharsets.UTF_8):key;this.baseUrl=baseUrl;
        var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(Duration.ofSeconds(2));f.setReadTimeout(Duration.ofSeconds(5));
        client=RestClient.builder().requestFactory(f).build();
    }
    @Override public WeatherObservation current(KmaGrid grid,Instant now) {
        if(key.isBlank()) throw new ApiProblem(503,"WEATHER_NOT_CONFIGURED","기상청 키 설정이 필요합니다.");
        // Conservative publication delay; if the latest observation is not published, inspect up to two previous hours.
        var base=now.atZone(KST).minusMinutes(40).withMinute(0).withSecond(0).withNano(0);
        List<Map<String,Object>> observations=null;Instant observed=null;
        for(int i=0;i<3;i++) {
            var b=base.minusHours(i);var rows=fetch("getUltraSrtNcst",b,grid);
            if(!rows.isEmpty()) { observations=rows;observed=b.toInstant();break; }
        }
        if(observations==null) throw new ApiProblem(502,"WEATHER_DATA_UNAVAILABLE","실황 자료가 없습니다.");
        var raw=new LinkedHashMap<String,String>();
        for(var r:observations) {
            if(r.get("category")!=null && r.get("obsrValue")!=null) raw.put(r.get("category").toString(),r.get("obsrValue").toString());
        }
        BigDecimal temperature=null;
        try { if(raw.containsKey("T1H")) temperature=new BigDecimal(raw.get("T1H")); } catch(NumberFormatException invalid) { throw unavailable(); }
        if(temperature!=null && (temperature.compareTo(new BigDecimal("-100"))<0 || temperature.compareTo(new BigDecimal("100"))>0)) throw unavailable();
        String sky=null;Instant skyAt=null;
        // SKY is not an observation category. Keep the valid-time interval separate from issuance time.
        try {
            var forecastBase=now.atZone(KST).minusMinutes(45).withMinute(30).withSecond(0).withNano(0);
            var forecasts=fetch("getUltraSrtFcst",forecastBase,grid);
            var target=now.atZone(KST).withMinute(0).withSecond(0).withNano(0);
            for(var f:forecasts) if("SKY".equals(f.get("category")) && target.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE).equals(f.get("fcstDate")) && target.format(DateTimeFormatter.ofPattern("HHmm")).equals(f.get("fcstTime"))) {
                sky=String.valueOf(f.get("fcstValue"));skyAt=target.toInstant();raw.put("SKY",sky);break;
            }
        } catch(ApiProblem unavailableForecast) { /* A missing optional SKY must not discard a valid observation. */ }
        var code=weatherCode(raw.get("PTY"),sky);
        var basis=Set.of("RAIN","SNOW","SLEET").contains(code)?"OBSERVATION":sky!=null && !"UNKNOWN".equals(code)?"FORECAST":"UNKNOWN";
        return new WeatherObservation(temperature,code,basis,observed,now,skyAt,raw);
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> fetch(String operation,ZonedDateTime base,KmaGrid grid) {
        try {
            URI uri=UriComponentsBuilder.fromUriString(baseUrl+"/"+operation).queryParam("serviceKey","{key}")
                .queryParam("dataType","JSON").queryParam("pageNo",1).queryParam("numOfRows",1000)
                .queryParam("base_date",base.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                .queryParam("base_time",base.format(DateTimeFormatter.ofPattern("HHmm"))).queryParam("nx",grid.x()).queryParam("ny",grid.y())
                .encode().buildAndExpand(Map.of("key",key)).toUri();
            var body=client.get().uri(uri).retrieve().body(Map.class);
            var response=(Map<String,Object>)Objects.requireNonNull(body).get("response");
            var header=(Map<String,Object>)response.get("header");var result=String.valueOf(header.get("resultCode"));
            if("03".equals(result)) return List.of();if(!"00".equals(result)) throw unavailable();
            var data=(Map<String,Object>)response.get("body");
            if(!(data.get("items") instanceof Map<?,?> items) || !(items.get("item") instanceof List<?> list)) return List.of();
            int total=Integer.parseInt(String.valueOf(data.get("totalCount")));if(total>1000) throw unavailable();
            for(var value:list) {
                if(!(value instanceof Map<?,?> row) || !base.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE).equals(String.valueOf(row.get("baseDate")))
                    || !base.format(DateTimeFormatter.ofPattern("HHmm")).equals(String.valueOf(row.get("baseTime")))
                    || grid.x()!=Integer.parseInt(String.valueOf(row.get("nx"))) || grid.y()!=Integer.parseInt(String.valueOf(row.get("ny")))) throw unavailable();
            }
            return (List<Map<String,Object>>)list;
        } catch(RuntimeException failure) { if(failure instanceof ApiProblem p) throw p;throw unavailable(); }
    }
    private ApiProblem unavailable() { return new ApiProblem(502,"WEATHER_PROVIDER_ERROR","외부 날씨 자료를 확인하지 못했습니다."); }
    public static String weatherCode(String pty,String sky) {
        if(pty==null) return "UNKNOWN";
        return switch(pty) { case "1","5" -> "RAIN";case "2","6" -> "SLEET";case "3","7" -> "SNOW";
            case "0" -> sky==null?"UNKNOWN":switch(sky) { case "1" -> "SUNNY";case "3" -> "CLOUDY";case "4" -> "OVERCAST";default -> "UNKNOWN";};default -> "UNKNOWN";};
    }
}

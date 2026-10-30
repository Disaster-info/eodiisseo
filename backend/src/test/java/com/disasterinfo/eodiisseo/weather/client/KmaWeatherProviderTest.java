package com.disasterinfo.eodiisseo.weather.client;
import com.disasterinfo.eodiisseo.weather.util.KmaGrid;
import com.sun.net.httpserver.HttpServer;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.object;

class KmaWeatherProviderTest {
    private HttpServer server;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicBoolean encodedOnce=new AtomicBoolean();
    private boolean observationError,forecastError;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            var params=new HashMap<String,String>();
            for(var pair:exchange.getRequestURI().getRawQuery().split("&")) { var parts=pair.split("=",2);params.put(parts[0],URLDecoder.decode(parts[1],StandardCharsets.UTF_8)); }
            encodedOnce.set("test+/key=".equals(params.get("serviceKey")));
            boolean forecast=exchange.getRequestURI().getPath().endsWith("getUltraSrtFcst");
            var items=new ArrayList<Map<String,Object>>();
            for(var category:forecast?List.of("SKY"):List.of("T1H","PTY")) {
                var row=object("baseDate",params.get("base_date"),"baseTime",params.get("base_time"),"nx",60,"ny",127,"category",category);
                if(forecast) {row.put("fcstDate","20261016");row.put("fcstTime","1200");row.put("fcstValue","3");}
                else row.put("obsrValue",category.equals("T1H")?"22.4":"0");items.add(row);
            }
            var response=object("response",object("header",object("resultCode",observationError?"30":"00"),"body",object("totalCount",items.size(),"items",object("item",items))));
            byte[] body=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(forecast&&forecastError?500:200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private KmaWeatherProvider provider() { return new KmaWeatherProvider("test%2B%2Fkey%3D","http://127.0.0.1:"+server.getAddress().getPort()); }
    @Test void usesKoreanForecastDateAndKeepsObservationAndSkyTimesSeparate() {
        var now=Instant.parse("2026-10-16T03:00:00Z");var weather=provider().current(new KmaGrid(60,127),now);
        assertEquals("CLOUDY",weather.code());assertEquals("FORECAST",weather.basis());
        assertEquals(Instant.parse("2026-10-16T02:00:00Z"),weather.observedAt());assertEquals(now,weather.skyValidAt());assertTrue(encodedOnce.get());
    }
    @Test void httpSuccessWithProviderErrorIsNotSuccessfulWeather() {
        observationError=true;assertEquals(502,assertThrows(ApiProblem.class,()->provider().current(new KmaGrid(60,127),Instant.parse("2026-10-16T03:00:00Z"))).status());
    }
    @Test void optionalForecastFailurePreservesObservationAndDoesNotInventSunny() {
        forecastError=true;var weather=provider().current(new KmaGrid(60,127),Instant.parse("2026-10-16T03:00:00Z"));
        assertEquals("UNKNOWN",weather.code());assertEquals("22.4",weather.temperature().toPlainString());assertNull(weather.skyValidAt());
    }
}

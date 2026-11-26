package com.disasterinfo.eodiisseo.weather.service;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.weather.client.WeatherProvider;
import com.disasterinfo.eodiisseo.weather.client.KmaWeatherProvider;
import com.disasterinfo.eodiisseo.weather.util.KmaGrid;
import com.disasterinfo.eodiisseo.weather.repository.WeatherRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import tools.jackson.databind.ObjectMapper;
import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WeatherServiceTest {
    private final OwnedDatabase db=mock(OwnedDatabase.class);private final WeatherProvider provider=mock(WeatherProvider.class);
    private final Instant now=Instant.parse("2026-10-16T03:00:00Z");
    @Test void rejectsAmbiguousIncompleteAndNonFiniteLocationsBeforeCallingProvider() {
        assertThrows(ApiProblem.class,()->WeatherService.validateInput(null,null,null));
        assertThrows(ApiProblem.class,()->WeatherService.validateInput(37.0,null,null));
        assertThrows(ApiProblem.class,()->WeatherService.validateInput(37.0,127.0,UUID.randomUUID()));
        assertThrows(ApiProblem.class,()->WeatherService.validateInput(Double.NaN,127.0,null));
    }
    @Test void noPrecipitationWithoutSkyDoesNotMeanSunny() {
        assertEquals("UNKNOWN",KmaWeatherProvider.weatherCode("0",null));assertEquals("UNKNOWN",KmaWeatherProvider.weatherCode("9","1"));
        assertEquals("SLEET",KmaWeatherProvider.weatherCode("6",null));assertEquals("RAIN",KmaWeatherProvider.weatherCode("5",null));assertEquals("SNOW",KmaWeatherProvider.weatherCode("7",null));
    }
    @Test void officialGridReferenceAndSeoulMatchKnownCoordinates() {
        assertEquals(new KmaGrid(43,136),KmaGrid.from(38,126));assertEquals(new KmaGrid(60,127),KmaGrid.from(37.5665,126.9780));
    }
    @Test void providerFailureReturnsOnlyFreshEnoughPersistedObservationAndMarksItStale() {
        var source=UUID.randomUUID();when(db.tableExists("data_source")).thenReturn(true);
        when(db.one(startsWith("select source_id"),eq("WEATHER_API"))).thenReturn(object("source_id",source));
        when(db.one(startsWith("select * from weather_observation"),eq(source),any(),any())).thenReturn(object("temperature_c",new BigDecimal("20.0"),"condition_code","UNKNOWN","condition_basis","UNKNOWN","observed_at",now.minusSeconds(10799),"collected_at",now.minusSeconds(1000),"sky_valid_at",null));
        when(provider.current(any(),eq(now))).thenThrow(new ApiProblem(502,"DOWN","provider down"));
        var service=new WeatherService(new WeatherRepository(db),provider,Clock.fixed(now,ZoneOffset.UTC),new ObjectMapper(),600,7200,10800,"WEATHER_API");
        assertEquals(true,service.current(UUID.randomUUID(),37.5665,126.9780,null).get("stale"));
        when(db.one(startsWith("select * from weather_observation"),eq(source),any(),any())).thenReturn(object("temperature_c",new BigDecimal("20.0"),"condition_code","UNKNOWN","condition_basis","UNKNOWN","observed_at",now.minusSeconds(10800),"collected_at",now.minusSeconds(1000),"sky_valid_at",null));
        assertEquals(502,assertThrows(ApiProblem.class,()->service.current(UUID.randomUUID(),37.5665,126.9780,null)).status());
    }

    @Test @Timeout(15) void concurrentColdCacheRequestStopsWaitingWithoutStartingAnotherProviderCall() throws Exception {
        var source=UUID.randomUUID();
        var repository=mock(WeatherRepository.class);
        when(repository.tableExists("data_source")).thenReturn(true);
        when(repository.findActiveWeatherSource("WEATHER_API")).thenReturn(object("source_id",source));
        when(repository.findLatestObservation(eq(source),anyInt(),anyInt())).thenReturn(null);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(provider.current(any(),eq(now))).thenAnswer(call->{
            entered.countDown();
            release.await();
            throw new ApiProblem(502,"DOWN","provider down");
        });
        var service=new WeatherService(repository,provider,Clock.fixed(now,ZoneOffset.UTC),
                new ObjectMapper(),600,7200,10800,"WEATHER_API");
        try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var leader=worker.submit(()->assertThrows(ApiProblem.class,()->service.currentCoordinates(37.5665,126.9780)));
            try {
                if (!entered.await(5,java.util.concurrent.TimeUnit.SECONDS)) {
                    fail("Leader did not reach provider: " + (leader.isDone() ? leader.get().code() : "still pending"));
                }
                var follower=assertThrows(ApiProblem.class,()->service.currentCoordinates(37.5665,126.9780));
                assertEquals(503,follower.status());
                assertEquals("WEATHER_REFRESH_IN_PROGRESS",follower.code());
            } finally { release.countDown(); }
            assertEquals(502,leader.get(2,java.util.concurrent.TimeUnit.SECONDS).status());
        }
        verify(provider,times(1)).current(any(),eq(now));
    }
}

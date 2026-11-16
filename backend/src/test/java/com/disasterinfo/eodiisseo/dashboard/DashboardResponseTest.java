package com.disasterinfo.eodiisseo.dashboard;

import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.BaseLocation;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.DisasterStatus;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.MajorDisaster;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.Weather;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.WeatherStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DashboardResponseTest {

    @Autowired
    private JsonMapper mapper;

    @Test
    void serializesTheExactApi521SuccessShapeUsingTheApplicationMapper() {
        DashboardResponse response = new DashboardResponse(
                new BaseLocation("의정부시", 37.7381, 127.0337),
                new Weather(new BigDecimal("22.4"), "RAIN", "비",
                        Instant.parse("2026-10-07T04:00:00Z"),
                        Instant.parse("2026-10-07T04:10:00Z"),
                        "OBSERVATION", null, false, "외출 시 우산을 준비하세요."),
                WeatherStatus.AVAILABLE,
                List.of(new MajorDisaster(UUID.fromString("bd8da281-d393-5f8b-9813-a6707b2d0919"),
                        "HEAVY_RAIN", "호우주의보", "의정부시", DisasterStatus.ONGOING, "WARNING",
                        Instant.parse("2026-10-07T03:20:00Z"),
                        Instant.parse("2026-10-07T04:05:00Z")))
        );

        assertThat(mapper.readTree(mapper.writeValueAsString(response)))
                .isEqualTo(mapper.readTree("""
                        {
                          "baseLocation": {"name":"의정부시","latitude":37.7381,"longitude":127.0337},
                          "weather": {
                            "temperature":22.4,"weatherCode":"RAIN","weatherText":"비",
                            "observedAt":"2026-10-07T04:00:00Z","collectedAt":"2026-10-07T04:10:00Z",
                            "conditionBasis":"OBSERVATION","skyValidAt":null,"stale":false,
                            "guidance":"외출 시 우산을 준비하세요."
                          },
                          "weatherStatus":"AVAILABLE",
                          "majorDisasters":[{
                            "disasterId":"bd8da281-d393-5f8b-9813-a6707b2d0919",
                            "type":"HEAVY_RAIN","title":"호우주의보","region":"의정부시",
                            "status":"ONGOING","severity":"WARNING",
                            "occurredAt":"2026-10-07T03:20:00Z","updatedAt":"2026-10-07T04:05:00Z"
                          }]
                        }
                        """));
    }

    @Test
    void nationwideResponseKeepsNullFieldsAndAnEmptyArray() {
        DashboardResponse response = new DashboardResponse(null, null, WeatherStatus.NOT_REQUESTED, List.of());

        assertThat(mapper.readTree(mapper.writeValueAsString(response)))
                .isEqualTo(mapper.readTree("""
                        {"baseLocation":null,"weather":null,"weatherStatus":"NOT_REQUESTED","majorDisasters":[]}
                        """));
    }

    @Test
    void nullableSourceValuesArePreservedRatherThanInventingWeatherOrSeverity() {
        DashboardResponse response = new DashboardResponse(
                new BaseLocation("의정부시", 37.7381, 127.0337),
                new Weather(null, "UNKNOWN", "알 수 없음",
                        Instant.parse("2026-10-07T04:00:00Z"),
                        Instant.parse("2026-10-07T04:10:00Z"),
                        "UNKNOWN", null, true, null),
                WeatherStatus.STALE,
                List.of(new MajorDisaster(UUID.fromString("bd8da281-d393-5f8b-9813-a6707b2d0919"),
                        "HEAVY_RAIN", "호우정보", null, DisasterStatus.OCCURRED, null,
                        Instant.parse("2026-10-07T03:20:00Z"),
                        Instant.parse("2026-10-07T04:05:00Z")))
        );

        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(json.get("weatherStatus").asString()).isEqualTo("STALE");
        assertThat(json.get("weather").has("temperature")).isTrue();
        assertThat(json.get("weather").get("temperature").isNull()).isTrue();
        assertThat(json.get("weather").get("skyValidAt").isNull()).isTrue();
        assertThat(json.get("weather").get("guidance").isNull()).isTrue();
        assertThat(json.get("weather").get("weatherCode").asString()).isEqualTo("UNKNOWN");
        assertThat(json.get("majorDisasters").get(0).get("severity").isNull()).isTrue();
        assertThat(json.get("majorDisasters").get(0).get("region").isNull()).isTrue();
        assertThat(json.get("majorDisasters").get(0).get("status").asString()).isEqualTo("OCCURRED");
    }

    @Test
    void listResponseUsesTheSpecifiedWrapperAndRetainsEndedStates() {
        var item = new MajorDisaster(UUID.fromString("bd8da281-d393-5f8b-9813-a6707b2d0919"),
                "HEAVY_RAIN", "호우정보", null, DisasterStatus.ENDED, null,
                Instant.parse("2026-10-07T03:20:00Z"), Instant.parse("2026-10-07T04:05:00Z"));
        var json = mapper.readTree(mapper.writeValueAsString(new com.disasterinfo.eodiisseo.dashboard.dto.DisasterListResponse(List.of(item))));

        assertThat(json.propertyNames()).containsExactly("disasters");
        assertThat(json.get("disasters").get(0).propertyNames()).containsExactlyInAnyOrder(
                "disasterId", "type", "title", "region", "status", "severity", "occurredAt", "updatedAt");
        assertThat(json.get("disasters").get(0).get("status").asString()).isEqualTo("ENDED");
        assertThat(json.has("page")).isFalse();
    }

    @Test
    void detailResponseMatchesTheApiWithoutAiOrInventedDisplayFields() {
        var response = new com.disasterinfo.eodiisseo.dashboard.dto.DisasterDetailResponse(
                UUID.fromString("bd8da281-d393-5f8b-9813-a6707b2d0919"),
                "HEAVY_RAIN", "호우주의보", "의정부시", Instant.parse("2026-10-07T03:20:00Z"),
                "WARNING", DisasterStatus.ONGOING, "테스트용 공식 원문",
                new com.disasterinfo.eodiisseo.dashboard.dto.DisasterDetailResponse.Source("기상청"),
                Instant.parse("2026-10-07T04:05:00Z"), "LATEST");

        assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(mapper.readTree("""
                {
                  "disasterId":"bd8da281-d393-5f8b-9813-a6707b2d0919",
                  "type":"HEAVY_RAIN","title":"호우주의보","region":"의정부시",
                  "occurredAt":"2026-10-07T03:20:00Z","severity":"WARNING","status":"ONGOING",
                  "officialContent":"테스트용 공식 원문","source":{"organization":"기상청"},
                  "updatedAt":"2026-10-07T04:05:00Z","dataStatus":"LATEST"
                }
                """));
    }
}

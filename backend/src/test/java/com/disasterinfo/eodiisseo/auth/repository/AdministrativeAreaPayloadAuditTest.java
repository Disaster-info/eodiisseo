package com.disasterinfo.eodiisseo.auth.repository;

import com.disasterinfo.eodiisseo.EodiisseoApplication;
import com.disasterinfo.eodiisseo.area.adapter.AreaDashboardLocationReader;
import com.disasterinfo.eodiisseo.area.adapter.AreaNotificationInterestAreas;
import com.disasterinfo.eodiisseo.area.service.AreaService;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import com.disasterinfo.eodiisseo.dashboard.DashboardException;
import com.disasterinfo.eodiisseo.disaster.service.DisasterAreaNormalizer;
import com.disasterinfo.eodiisseo.group.repository.GroupRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static com.disasterinfo.eodiisseo.auth.repository.CanonicalDatabaseIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "EODIISSEO_AREA_AUDIT_PAYLOAD", matches = ".+")
class AdministrativeAreaPayloadAuditTest {
    @Test @Timeout(240)
    void verifiedPayloadUsesOpsHttpAndResolvesOnlyUniqueSpatialMatches() throws Exception {
        createEmptyDatabase();
        try (var context = new SpringApplicationBuilder(EodiisseoApplication.class)
                .web(WebApplicationType.SERVLET).run(
                        "--server.address=127.0.0.1", "--server.port=0",
                        "--spring.profiles.active=local-db-verification",
                        "--spring.datasource.url=" + source.getUrl(),
                        "--spring.datasource.username=eodiisseo_audit", "--spring.datasource.password=",
                        "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.flyway.locations=classpath:db/migration,classpath:db/owned-runtime",
                        "--AUTH_BRIDGES_ENABLED=true", "--eodiisseo.ai.ollama.enabled=false",
                        "--eodiisseo.fcm.enabled=false", "--debug=false", "--logging.level.root=WARN");
             var http = HttpClient.newHttpClient()) {
            Instant now = Instant.now();
            UUID member = member();
            UUID session = UUID.randomUUID();
            db.update("insert into auth_session values(?,?,?,?,?,null)", session, member,
                    AuthService.hash("test-only-" + session), now, now.plus(Duration.ofDays(14)));
            String token = context.getBean(TokenService.class).issue(member, session, now.plus(Duration.ofHours(1)));
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/api/v1/ops/admin-areas/import");
            Path payload = Path.of(System.getenv("EODIISSEO_AREA_AUDIT_PAYLOAD"));
            boolean withCities = payload.getFileName().toString().endsWith(".candidate.json");
            int expectedRows = withCities ? 285 : 272;
            assertEquals(403, post(http, endpoint, token, HttpRequest.BodyPublishers.ofString("{}")).statusCode());
            db.update("insert into member_role values(?,'OPS',?)", member, now);

            ObjectMapper json = context.getBean(ObjectMapper.class);
            String previous = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                long start = System.nanoTime();
                var response = post(http, endpoint, token, HttpRequest.BodyPublishers.ofFile(payload));
                assertEquals(200, response.statusCode(), () -> response.body().substring(0, Math.min(response.body().length(), 500)));
                assertEquals(expectedRows, json.readTree(response.body()).get("importedCount").asInt());
                assertEquals(expectedRows, db.count("select count(*) from admin_area where ST_IsValid(boundary) and ST_Covers(boundary,center_point::geometry)"));
                assertEquals(256, db.count("""
                        select count(*) from admin_area a join admin_area p on a.parent_area_code=p.area_code
                        where a.level='SIGUNGU' and a.boundary_source_provider='VWORLD_SIG'
                          and p.boundary_source_provider in ('VWORLD_SIG_DERIVED','VWORLD_SIG_CITY_DERIVED')
                        """));
                String fingerprint = (String) db.one("""
                        select md5(string_agg(area_code||coalesce(parent_area_code,'')||name||md5(ST_AsEWKB(boundary)),
                                              ':' order by area_code)) fingerprint from admin_area
                        """).get("fingerprint");
                if (previous != null) assertEquals(previous, fingerprint);
                previous = fingerprint;
                System.out.println("ADMIN_AREA_AUDIT attempt=" + (attempt + 1)
                        + " ms=" + Duration.ofNanos(System.nanoTime()-start).toMillis());
            }

            var locations = context.getBean(AreaDashboardLocationReader.class);
            assertEquals("의정부시", locations.resolveCoordinates(37.738,127.033).name());
            var overlap = db.one("""
                    select ST_Y(p) latitude,ST_X(p) longitude from (
                      select ST_PointOnSurface(ST_Intersection(a.boundary,b.boundary)) p
                      from admin_area a,admin_area b where a.area_code='12790' and b.area_code='12800'
                    ) t
                    """);
            assertThrows(DashboardException.class, () -> locations.resolveCoordinates(
                    ((Number) overlap.get("latitude")).doubleValue(), ((Number) overlap.get("longitude")).doubleValue()));
            assertThrows(DashboardException.class, () -> locations.resolveCoordinates(0,0));
            if (withCities) verifyCityHierarchy(context.getBean(AreaService.class),member,now,locations);

            String failed = """
                    {"areas":[
                      {"regionCode":"AUDIT-ROLLBACK-1","name":"test","level":"SIDO","active":true,
                      "sourceUrl":"https://example.org/source","sourceProvider":"AUDIT","sourceAreaCode":"1",
                      "baseYear":2020,"sourceUpdatedAt":"2020-01-01T00:00:00Z"},
                      {"regionCode":"AUDIT-ROLLBACK-2","name":"test","level":"SIGUNGU","active":true,
                      "parentRegionCode":"AUDIT-MISSING","sourceUrl":"https://example.org/source",
                      "sourceProvider":"AUDIT","sourceAreaCode":"2","baseYear":2020,"sourceUpdatedAt":"2020-01-01T00:00:00Z"}
                    ]}
                    """;
            assertEquals(400, post(http, endpoint, token, HttpRequest.BodyPublishers.ofString(failed)).statusCode());
            assertEquals(0, db.count("select count(*) from admin_area where area_code like 'AUDIT-ROLLBACK-%'"));
        } finally {
            dropOnlyCreatedDatabase();
        }
    }

    private static void verifyCityHierarchy(AreaService areas, UUID member, Instant now,
                                            AreaDashboardLocationReader locations) {
        assertEquals(39, db.count("select count(*) from admin_area a join admin_area p on p.area_code=a.parent_area_code where p.boundary_source_provider='VWORLD_SIG_CITY_DERIVED'"));
        var normalizer = new DisasterAreaNormalizer(db);
        var impact = DisasterAreaNormalizer.Meaning.OFFICIAL_IMPACT;
        assertEquals("41110", normalizer.byAddress("경기도 수원시",impact).orElseThrow().sigungu());
        assertEquals("41111", normalizer.byAddress("경기도 수원시 장안구",impact).orElseThrow().sigungu());
        assertEquals("36110", normalizer.byCode("3600000000",impact).orElseThrow().sigungu());
        assertEquals("36110", normalizer.byAddress("세종특별자치시",impact).orElseThrow().sigungu());
        assertTrue(normalizer.byCode("29110",impact).isEmpty());
        assertEquals("12110", normalizer.byCode("1211000000",impact).orElseThrow().sigungu());
        assertEquals("제주시",db.one("select name from admin_area where area_code='50110'").get("name"));
        assertEquals("완산구",db.one("select name from admin_area where area_code='52111'").get("name"));
        var center = db.one("select ST_Y(center_point::geometry) lat,ST_X(center_point::geometry) lon from admin_area where area_code='41111'");
        assertEquals("장안구",locations.resolveCoordinates(((Number) center.get("lat")).doubleValue(),
                ((Number) center.get("lon")).doubleValue()).name());
        var city = areas.create(member,"4111000000","수원시");
        var ward = areas.create(member,"41111","장안구");
        var sibling = areas.create(member,"41113","권선구");
        assertEquals("41110",city.get("regionCode"));
        assertEquals(3,((java.util.List<?>) areas.list(member).get("interestAreas")).size());
        UUID disaster = UUID.randomUUID();
        db.update("insert into disaster(disaster_id,disaster_type_id,title,occurred_at,status,data_origin,updated_at) values(?,'10000000-0000-0000-0000-000000000001','audit',?,'ONGOING','OFFICIAL',?)",disaster,now,now);
        UUID source = UUID.randomUUID(), record = UUID.randomUUID();
        db.update("insert into data_source(source_id,name,source_kind,endpoint_key,active) values(?,'audit','DISASTER',?,true)",source,"audit-"+source);
        db.update("insert into source_disaster(source_disaster_id,disaster_id,source_id,source_record_key,original_text,collected_at,content_hash) values(?,?,?,'audit','audit',?,?)",record,disaster,source,now,"a".repeat(64));
        db.update("insert into disaster_area values(?,?,?,'IMPACT','OFFICIAL',?,?)",UUID.randomUUID(),disaster,"41110",record,now);
        var related = new AreaNotificationInterestAreas(db);
        assertTrue(related.isRelated(disaster,(UUID) ward.get("interestAreaId")));
        assertTrue(related.isRelated(disaster,(UUID) sibling.get("interestAreaId")));
        var groupAreas = new GroupRepository(db);
        assertTrue(groupAreas.findImpactCoverage(center.get("lon"),center.get("lat"),disaster)
                .stream().anyMatch(row -> Boolean.TRUE.equals(row.get("included"))));
        db.update("update disaster_area set area_code='41111' where disaster_id=?",disaster);
        assertTrue(related.isRelated(disaster,(UUID) city.get("interestAreaId")));
        assertFalse(related.isRelated(disaster,(UUID) sibling.get("interestAreaId")));
        var boundaryOverlap = db.one("""
                select ST_X(point) lon,ST_Y(point) lat from (
                  select ST_PointOnSurface(ST_Intersection(a.boundary,b.boundary)) point
                  from admin_area a,admin_area b where a.area_code='12790' and b.area_code='12800'
                ) t
                """);
        db.update("update disaster_area set area_code='12790' where disaster_id=?",disaster);
        assertTrue(groupAreas.findImpactCoverage(boundaryOverlap.get("lon"),boundaryOverlap.get("lat"),disaster)
                .stream().anyMatch(row -> Boolean.TRUE.equals(row.get("included"))));
    }

    private static HttpResponse<String> post(HttpClient http, URI endpoint, String token,
                                             HttpRequest.BodyPublisher body) throws Exception {
        return http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }
}

package com.disasterinfo.eodiisseo.tools;

import com.disasterinfo.eodiisseo.area.controller.AdministrativeAreaController.Import;
import com.disasterinfo.eodiisseo.area.dto.AdministrativeAreaInput;
import com.disasterinfo.eodiisseo.area.service.AdministrativeAreaImportService;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Database operator entry point, excluded from the Spring Boot artifact. */
public final class LocalAdministrativeAreaImport {
    private static final int COUNT = 285;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        boolean audit = args.length == 2 && args[0].equals("audit");
        boolean apply = args.length == 2 && args[0].equals("apply-development");
        if (!audit && !apply) throw new IllegalArgumentException("Use audit or apply-development with an approval directory.");
        var directory = Path.of(args[1]);
        var rows = readApproved(directory);
        String database = audit ? System.getenv("EODIISSEO_LOCAL_AREA_AUDIT_DB") : "eodiisseo_local";
        if (audit && (database == null || !database.matches("area_direct_[a-f0-9]{32}"))) {
            throw new IllegalStateException("Expected a disposable Audit database name.");
        }
        int port = audit ? 55433 : 55432;
        String user = audit ? "eodiisseo_audit" : "eodiisseo_app";
        String password = audit ? "" : System.getenv("EODIISSEO_LOCAL_AREA_DB_PASSWORD");
        if (apply && (password == null || password.isBlank())) throw new IllegalStateException("Local database credential is required.");
        var source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + port + "/" + database, user, password);
        var jdbc = new JdbcTemplate(source);
        var identity = jdbc.queryForObject("select current_database() || ':' || inet_server_port() || ':' || current_user",String.class);
        if (!(database + ":" + port + ":" + user).equals(identity)) throw new IllegalStateException("Unexpected database identity.");
        if (apply) verifyBackup(directory);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var importer = new AdministrativeAreaImportService(new OwnedDatabase(jdbc), JSON, Clock.systemUTC());
        String fingerprint = importAndCheck(jdbc, transaction, importer, rows);
        if (audit) {
            if (!fingerprint.equals(importAndCheck(jdbc, transaction, importer, rows))) {
                throw new IllegalStateException("Repeated import changed the dataset fingerprint.");
            }
            verifyRollback(jdbc, transaction, importer, rows.getLast(), fingerprint);
        }
        System.out.printf("rows=%d fingerprint=%s mode=%s%n",COUNT,fingerprint,audit ? "audit-repeat-and-rollback" : "development");
    }

    private static List<AdministrativeAreaInput> readApproved(Path directory) throws Exception {
        var manifestBytes = Files.readAllBytes(directory.resolve("approval.json"));
        verifyHash(manifestBytes,System.getenv("EODIISSEO_LOCAL_AREA_MANIFEST_SHA256"));
        var manifest = JSON.readTree(manifestBytes);
        var approved = directory.resolve("areas.approved.json");
        if (Files.size(approved) != manifest.path("bytes").asLong() || Files.size(approved) > 80L * 1024 * 1024
                || !"APPROVED".equals(manifest.path("status").asString())
                || !"local-development".equals(manifest.path("target").asString())
                || !"http://127.0.0.1:8081/".equals(manifest.path("origin").asString())
                || manifest.path("reviewReference").asString("").isBlank() || manifest.path("rows").asInt() != COUNT) {
            throw new IllegalArgumentException("The approval manifest does not authorize this local dataset.");
        }
        byte[] bytes = Files.readAllBytes(approved);
        verifyHash(bytes,System.getenv("EODIISSEO_LOCAL_AREA_PAYLOAD_SHA256"));
        verifyHash(bytes,manifest.path("payloadSha256").asString());
        var payload = JSON.readValue(bytes,Import.class);
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            if (!validators.getValidator().validate(payload).isEmpty() || payload.areas().size() != COUNT) {
                throw new IllegalArgumentException("Payload failed the OPS DTO validation or row count.");
            }
        }
        return payload.areas();
    }

    private static void verifyBackup(Path directory) throws Exception {
        Path evidence = Path.of(System.getenv("EODIISSEO_LOCAL_AREA_BACKUP_EVIDENCE"));
        var result = JSON.readTree(Files.readAllBytes(evidence));
        var dump = Path.of(result.path("dump").asString());
        Instant now = Instant.now();
        Instant dumpedAt = Files.getLastModifiedTime(dump).toInstant();
        if (!"PASS".equals(result.path("restore").asString())
                || !"127.0.0.1:55433".equals(result.path("restoreTarget").asString())
                || !"eodiisseo_local:55432:eodiisseo_app".equals(result.path("developmentTarget").asString())
                || dumpedAt.isAfter(now) || Duration.between(dumpedAt,now).compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalStateException("A fresh, successfully rehearsed development backup is required.");
        }
        String expected = System.getenv("EODIISSEO_LOCAL_AREA_BACKUP_SHA256");
        var manifest = Files.readAllBytes(directory.resolve("approval.json"));
        verifyHash(manifest,System.getenv("EODIISSEO_LOCAL_AREA_MANIFEST_SHA256"));
        if (expected == null || !expected.equalsIgnoreCase(JSON.readTree(manifest).path("backupSha256").asString())) {
            throw new IllegalStateException("Approval does not identify the current backup.");
        }
        verifyHash(Files.readAllBytes(dump),expected);
        if (!expected.equalsIgnoreCase(result.path("sha256").asString())) throw new IllegalStateException("Backup evidence hash differs.");
    }

    private static void verifyHash(byte[] bytes, String expected) throws Exception {
        if (expected == null || !expected.matches("[a-fA-F0-9]{64}") ||
                !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equalsIgnoreCase(expected)) {
            throw new IllegalArgumentException("SHA-256 validation failed.");
        }
    }

    private static String importAndCheck(JdbcTemplate jdbc, TransactionTemplate tx,
                                         AdministrativeAreaImportService importer, List<AdministrativeAreaInput> rows) {
        return tx.execute(status -> {
            if (importer.importAreas(rows) != COUNT) throw new IllegalStateException("Unexpected import count.");
            var totals = jdbc.queryForMap("""
                    select count(*) total, count(*) filter(where a.level='SIDO') provinces,
                      count(*) filter(where a.level='SIGUNGU') districts,
                      count(*) filter(where a.boundary_source_provider='VWORLD_SIG_CITY_DERIVED') cities,
                      count(*) filter(where p.boundary_source_provider='VWORLD_SIG_CITY_DERIVED') wards,
                      count(*) filter(where not a.active or a.boundary is null or ST_IsEmpty(a.boundary)
                         or not ST_IsValid(a.boundary) or ST_SRID(a.boundary)<>4326 or a.center_point is null
                         or not ST_Covers(a.boundary,a.center_point::geometry)
                         or (a.parent_area_code is not null and p.active is distinct from true)) invalid
                    from admin_area a left join admin_area p on p.area_code=a.parent_area_code
                    """);
            if (!totals.equals(java.util.Map.of("total",285L,"provinces",16L,"districts",269L,
                    "cities",13L,"wards",39L,"invalid",0L))) throw new IllegalStateException("Post-import integrity check failed: " + totals);
            for (var row : rows) {
                Boolean matches = jdbc.queryForObject("""
                        select name=? and level=? and parent_area_code is not distinct from ? and active=?
                          and boundary_source_url=? and boundary_source_provider=? and boundary_source_area_code=?
                          and boundary_base_year=? and boundary_source_updated_at=?
                          and ST_Y(center_point::geometry)=? and ST_X(center_point::geometry)=?
                          and ST_AsEWKB(boundary)=ST_AsEWKB(ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?),4326)))
                        from admin_area where area_code=?
                        """,Boolean.class,row.name(),row.level(),row.parentRegionCode(),row.active(),row.sourceUrl(),
                        row.sourceProvider(),row.sourceAreaCode(),row.baseYear(),java.sql.Timestamp.from(row.sourceUpdatedAt()),
                        row.latitude(),row.longitude(),JSON.writeValueAsString(row.boundary()),row.regionCode());
                if (!Boolean.TRUE.equals(matches)) throw new IllegalStateException("Stored row differs: " + row.regionCode());
            }
            return fingerprint(jdbc);
        });
    }

    private static String fingerprint(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select encode(sha256(convert_to(string_agg(
                  (to_jsonb(a) - 'boundary_collected_at' - 'boundary' - 'center_point')::text ||
                  encode(ST_AsEWKB(boundary),'hex') || encode(ST_AsEWKB(center_point::geometry),'hex'),
                  E'\\n' order by area_code),'UTF8')),'hex')
                from admin_area a
                """,String.class);
    }

    private static void verifyRollback(JdbcTemplate jdbc, TransactionTemplate tx,
                                       AdministrativeAreaImportService importer, AdministrativeAreaInput original,
                                       String before) {
        var timestamps = jdbc.queryForList("select boundary_collected_at from admin_area order by area_code");
        var invalid = new AdministrativeAreaInput("AUDIT-ROLLBACK",original.name(),original.level(),
                "NO-SUCH-PARENT",true,original.sourceUrl(),original.sourceProvider(),original.sourceAreaCode(),
                original.baseYear(),original.sourceUpdatedAt(),original.latitude(),original.longitude(),original.boundary());
        try {
            tx.executeWithoutResult(status -> importer.importAreas(List.of(original,invalid)));
            throw new IllegalStateException("Invalid parent did not abort the database transaction.");
        } catch (ApiProblem expected) {
            if (expected.status() != 400 || !before.equals(fingerprint(jdbc))
                    || !timestamps.equals(jdbc.queryForList("select boundary_collected_at from admin_area order by area_code"))) {
                throw new IllegalStateException("Failed import changed the dataset.");
            }
        }
    }
}

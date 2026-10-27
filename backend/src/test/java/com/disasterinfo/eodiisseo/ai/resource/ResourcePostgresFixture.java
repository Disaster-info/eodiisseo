package com.disasterinfo.eodiisseo.ai.resource;

import com.disasterinfo.eodiisseo.ai.resource.adapter.JdbcOfficialResourceStore;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

/** Disposable loopback PostgreSQL only. This is fixture DDL, never a production migration. */
public final class ResourcePostgresFixture implements AutoCloseable {
    private final String schema = "resource_audit_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate administrative;
    private final DataSource dataSource;

    public ResourcePostgresFixture() {
        String url = System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        Assumptions.assumeTrue(url != null && "true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")),
                "An explicitly disposable PostgreSQL audit cluster is required.");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
            throw new IllegalArgumentException("Only a disposable loopback PostgreSQL fixture is allowed.");
        }
        administrative = new JdbcTemplate(new DriverManagerDataSource(url, "eodiisseo_audit", ""));
        administrative.execute("create schema " + schema);
        dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema, "eodiisseo_audit", "");
        try {
            new JdbcTemplate(dataSource).execute("""
                    create table official_resource(
                      resource_id uuid primary key, agency_name varchar(100) not null,
                      title varchar(255) not null, source_url text not null, version_label varchar(100),
                      content_text text, checked_at timestamptz not null)
                    """);
        } catch (RuntimeException failure) { close(); throw failure; }
    }

    public DataSource dataSource() { return dataSource; }
    public JdbcOfficialResourceStore store() { return new JdbcOfficialResourceStore(Optional.of(dataSource)); }
    public JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }

    @Override public void close() {
        // Only this locally generated, fixed-format disposable fixture schema is removed.
        if (!schema.matches("resource_audit_[a-f0-9]{32}")) throw new IllegalStateException("Invalid fixture schema.");
        administrative.execute("drop schema if exists " + schema + " cascade");
    }
}

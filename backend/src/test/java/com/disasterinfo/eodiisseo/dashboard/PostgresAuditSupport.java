package com.disasterinfo.eodiisseo.dashboard;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Assumptions;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Real PostgreSQL tests against a disposable, explicitly opted-in loopback cluster.
 * Owned tables follow ERD 4.2.1. Other-domain tables are minimal FK/query fixtures,
 * not migrations, production domain entities or proof of the deployed schema.
 */
public final class PostgresAuditSupport implements AutoCloseable {
    private static final String USER = "eodiisseo_audit";
    private final String baseUrl;
    private final String schema = "audit_" + UUID.randomUUID().toString().replace("-", "");
    private final String schemaUrl;
    private final EntityManagerFactory factory;
    private final TransactionTemplate transactions;

    public PostgresAuditSupport() throws SQLException {
        baseUrl = System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        Assumptions.assumeTrue(baseUrl != null && "true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")),
                "An explicitly disposable PostgreSQL audit cluster is required.");
        if (!baseUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
            throw new IllegalArgumentException("Audit fixtures are restricted to a disposable loopback cluster.");
        }
        schemaUrl = baseUrl + "?currentSchema=" + schema;
        try {
            try (Connection connection = connection(); var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("SET search_path TO " + schema);
                statement.execute(DDL);
            }
            var dataSource = new DriverManagerDataSource(schemaUrl, USER, "");
            var builder = new LocalContainerEntityManagerFactoryBean();
            builder.setPersistenceUnitName(schema);
            builder.setDataSource(dataSource);
            builder.setPackagesToScan("com.disasterinfo.eodiisseo.ai.summary.entity",
                    "com.disasterinfo.eodiisseo.ai.chat.entity", "com.disasterinfo.eodiisseo.notification.persistence");
            builder.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            builder.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.jdbc.time_zone", "UTC", "hibernate.show_sql", "false"));
            builder.afterPropertiesSet();
            factory = Objects.requireNonNull(builder.getObject());
            transactions = new TransactionTemplate(new JpaTransactionManager(factory));
        } catch (RuntimeException | SQLException failure) {
            try {
                dropFixtureSchema();
            } catch (SQLException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public <T> T tx(Function<EntityManager, T> action) {
        return transactions.execute(status -> action.apply(Objects.requireNonNull(
                EntityManagerFactoryUtils.getTransactionalEntityManager(factory))));
    }

    public <T> T repository(EntityManager em, Class<T> type) {
        return new JpaRepositoryFactory(em).getRepository(type);
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(baseUrl, USER, "");
    }

    public Connection schemaConnection() throws SQLException {
        return DriverManager.getConnection(schemaUrl, USER, "");
    }

    public String jdbcUrl() {
        return schemaUrl;
    }

    public void sql(String statement) {
        try (Connection connection = schemaConnection(); var query = connection.createStatement()) {
            query.execute(statement);
        } catch (SQLException failure) {
            throw new IllegalStateException("Disposable PostgreSQL fixture operation failed.", failure);
        }
    }

    public long count(String table) {
        if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid fixture table.");
        try (Connection connection = schemaConnection(); var query = connection.createStatement();
             var result = query.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("Disposable PostgreSQL fixture query failed.", failure);
        }
    }

    @Override
    public void close() throws SQLException {
        factory.close();
        dropFixtureSchema();
    }

    private void dropFixtureSchema() throws SQLException {
        // This identifier is generated locally, never supplied by a request or connection setting.
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static final String DDL = """
            CREATE TABLE member(member_id uuid PRIMARY KEY, status varchar(20) NOT NULL);
            CREATE TABLE severity_level(severity_id uuid PRIMARY KEY, code varchar(30) UNIQUE NOT NULL,
              label varchar(50) NOT NULL, rank smallint UNIQUE NOT NULL);
            CREATE TABLE disaster_type(disaster_type_id uuid PRIMARY KEY, code varchar(30) UNIQUE NOT NULL,
              name varchar(50) NOT NULL, active boolean NOT NULL);
            CREATE TABLE disaster(disaster_id uuid PRIMARY KEY, disaster_type_id uuid NOT NULL REFERENCES disaster_type,
              title varchar(200) NOT NULL, occurred_at timestamptz NOT NULL,
              status varchar(15) NOT NULL CHECK(status IN ('OCCURRED','ONGOING','ENDED','LIFTED')),
              official_severity_id uuid REFERENCES severity_level, data_origin varchar(10) NOT NULL,
              updated_at timestamptz NOT NULL, ended_at timestamptz,
              CHECK(status NOT IN ('ENDED','LIFTED') OR ended_at IS NOT NULL));
            CREATE TABLE source_disaster(source_disaster_id uuid PRIMARY KEY, disaster_id uuid NOT NULL REFERENCES disaster,
              source_id uuid NOT NULL, source_record_key varchar(255) NOT NULL, original_text text NOT NULL,
              collected_at timestamptz NOT NULL, content_hash char(64) NOT NULL);
            CREATE TABLE source_disaster_revision(revision_id uuid PRIMARY KEY,
              source_disaster_id uuid NOT NULL REFERENCES source_disaster, revision_no integer NOT NULL,
              original_text text NOT NULL, content_hash char(64) NOT NULL, observed_at timestamptz NOT NULL,
              UNIQUE(source_disaster_id, revision_no));
            CREATE TABLE official_resource(resource_id uuid PRIMARY KEY);
            CREATE TABLE ai_summary(summary_id uuid PRIMARY KEY, disaster_id uuid NOT NULL REFERENCES disaster,
              source_revision_id uuid UNIQUE NOT NULL REFERENCES source_disaster_revision,
              status varchar(12) NOT NULL CHECK(status IN ('PENDING','COMPLETED','FAILED','STALE')),
              summary_text text, model_name varchar(100), attempt_count smallint NOT NULL DEFAULT 0,
              created_at timestamptz NOT NULL, completed_at timestamptz);
            CREATE TABLE ai_answer(answer_id uuid PRIMARY KEY, member_id uuid NOT NULL REFERENCES member,
              question_text text NOT NULL, answer_text text, status varchar(15) NOT NULL,
              model_name varchar(100), created_at timestamptz NOT NULL, completed_at timestamptz,
              expires_at timestamptz NOT NULL);
            CREATE TABLE ai_answer_resource(answer_id uuid NOT NULL REFERENCES ai_answer,
              resource_id uuid NOT NULL REFERENCES official_resource, PRIMARY KEY(answer_id, resource_id));
            CREATE TABLE notification_setting(member_id uuid PRIMARY KEY REFERENCES member, enabled boolean NOT NULL,
              minimum_severity_id uuid REFERENCES severity_level, allow_after_22 boolean NOT NULL,
              updated_at timestamptz NOT NULL);
            CREATE TABLE device_token(device_token_id uuid PRIMARY KEY, member_id uuid NOT NULL REFERENCES member,
              token_hash char(64) UNIQUE NOT NULL, token_ciphertext text NOT NULL, platform varchar(10) NOT NULL,
              active boolean NOT NULL, updated_at timestamptz NOT NULL, device_id varchar(100) UNIQUE NOT NULL);
            CREATE TABLE notification_event(event_id uuid PRIMARY KEY, member_id uuid REFERENCES member,
              disaster_id uuid REFERENCES disaster, event_key varchar(80) NOT NULL, event_kind varchar(20) NOT NULL,
              severity_id uuid REFERENCES severity_level, disaster_title_snapshot varchar(200) NOT NULL,
              created_at timestamptz NOT NULL, retention_until timestamptz NOT NULL, deidentified_at timestamptz);
            CREATE UNIQUE INDEX uq_active_notification_event ON notification_event(member_id, disaster_id, event_key)
              WHERE member_id IS NOT NULL;
            CREATE TABLE notification_delivery(delivery_id uuid PRIMARY KEY,
              event_id uuid NOT NULL REFERENCES notification_event, device_token_id uuid REFERENCES device_token,
              target_ordinal smallint NOT NULL, attempt_no smallint NOT NULL, sent_at timestamptz,
              result varchar(12) NOT NULL, provider_message_id varchar(255), error_code varchar(80),
              UNIQUE(event_id,target_ordinal,attempt_no));
            """;
}

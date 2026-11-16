package com.disasterinfo.eodiisseo.disaster.collection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Setup regression only: no credentials, network transport, Spring server or live-test execution. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
class EarthquakeProbeSetupIntegrationTest {
    @Test void canonicalSourceIsReusedWithoutMutationAndAbsentSourceIsCreatedOnlyOnce(){
        String base=System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")) ||
            !"jdbc:postgresql://127.0.0.1:55433/postgres".equals(base))
            throw new IllegalStateException("Disposable loopback audit cluster required");
        String name="owned_"+UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(base,"eodiisseo_audit",""));
        admin.execute("CREATE DATABASE "+name);
        try{
            var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+name,"eodiisseo_audit","");
            Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime").load().migrate();
            var sql=new JdbcTemplate(ds);
            var quake=OfficialProviderClient.Provider.EARTHQUAKE;
            var before=sql.queryForMap("select * from data_source where endpoint_key=?",quake.endpointKey);
            long count=sql.queryForObject("select count(*) from data_source",Long.class);
            UUID selected=OfficialDevelopmentLiveValidationTest.developmentSource(sql,quake);
            assertThat(selected).isEqualTo(before.get("source_id"));
            assertThat(OfficialDevelopmentLiveValidationTest.developmentSource(sql,quake)).isEqualTo(selected);
            assertThat(sql.queryForMap("select * from data_source where source_id=?",selected)).isEqualTo(before);
            assertThat(sql.queryForObject("select count(*) from data_source",Long.class)).isEqualTo(count);
            var rain=OfficialProviderClient.Provider.HEAVY_RAIN;
            UUID created=OfficialDevelopmentLiveValidationTest.developmentSource(sql,rain);
            assertThat(OfficialDevelopmentLiveValidationTest.developmentSource(sql,rain)).isEqualTo(created);
            assertThat(sql.queryForObject("select count(*) from data_source",Long.class)).isEqualTo(count+1);

            sql.update("update data_source set active=false where source_id=?",selected);
            assertThatThrownBy(()->OfficialDevelopmentLiveValidationTest.developmentSource(sql,quake))
                .isInstanceOf(AssertionError.class);
            assertThat(sql.queryForObject("select active from data_source where source_id=?",Boolean.class,selected)).isFalse();
            sql.update("update data_source set active=true,source_kind='WEATHER' where source_id=?",selected);
            assertThatThrownBy(()->OfficialDevelopmentLiveValidationTest.developmentSource(sql,quake))
                .isInstanceOf(AssertionError.class);
            assertThat(sql.queryForObject("select source_kind from data_source where source_id=?",String.class,selected)).isEqualTo("WEATHER");
            assertThat(sql.queryForObject("select count(*) from collection_run",Long.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class)).isZero();
        }finally{
            if(!name.matches("owned_[a-f0-9]{32}"))throw new IllegalStateException("Invalid disposable name");
            admin.execute("DROP DATABASE "+name+" WITH (FORCE)");
        }
    }
}

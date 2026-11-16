package com.disasterinfo.eodiisseo.disaster.service;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;

/** Enabling owned data adapters requires explicitly applied owned migrations, never a schema shortcut. */
@Component
@ConditionalOnProperty(name="eodiisseo.owned-runtime.enabled",havingValue="true")
public class OwnedRuntimeSchemaGuard implements SmartInitializingSingleton {
    private final JdbcTemplate sql;
    public OwnedRuntimeSchemaGuard(JdbcTemplate sql){this.sql=sql;}
    public void afterSingletonsInstantiated() {
        for(String table:new String[]{"disaster_source_priority","disaster_runtime_outbox","summary_runtime_task","ai_resource_review","ai_external_consent","owned_collection_cursor","owned_collection_page","owned_collection_item","owned_resource_import","owned_incident_head","owned_collection_coverage"})
            if(sql.queryForObject("select to_regclass(?)::text",String.class,table)==null)throw new IllegalStateException("Owned runtime migrations are required.");
        if(sql.queryForObject("select count(*) from information_schema.columns where table_schema=current_schema() and table_name='ai_external_consent' and column_name='granted_session_id'",Long.class)!=1L)
            throw new IllegalStateException("Session-bound consent migration is required.");
    }
}

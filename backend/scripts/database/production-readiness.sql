\set ON_ERROR_STOP on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '2s';

SELECT current_setting('server_version') AS postgres_version;
SELECT name,default_version,installed_version FROM pg_available_extensions
WHERE name IN ('postgis','vector') ORDER BY name;
SELECT to_regclass('flyway_schema_history') IS NOT NULL AS has_flyway,
       to_regclass('admin_area') IS NOT NULL AS has_areas,
       to_regclass('collection_source_assignment') IS NOT NULL AS has_assignments,
       to_regclass('rag_vector_embedding') IS NOT NULL AS has_vectors
\gset
\if :has_flyway
SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;
\else
\echo FLYWAY_HISTORY_MISSING
\endif
\if :has_areas
SELECT a.level,a.active,a.boundary_source_provider,count(*) AS areas,
       count(*) FILTER (WHERE a.boundary IS NULL OR ST_IsEmpty(a.boundary)
                    OR NOT ST_IsValid(a.boundary) OR ST_SRID(a.boundary)<>4326) AS missing_or_invalid_boundary,
       count(*) FILTER (WHERE a.parent_area_code IS NOT NULL AND p.active IS DISTINCT FROM TRUE) AS inactive_or_missing_parent
FROM admin_area a LEFT JOIN admin_area p ON p.area_code=a.parent_area_code
GROUP BY 1,2,3 ORDER BY 1,2,3;
SELECT count(*) AS legacy_29_46_areas FROM admin_area WHERE area_code ~ '^(29|46)';
\else
\echo ADMIN_AREA_SCHEMA_MISSING
\endif
SELECT ds.endpoint_key, count(s.shelter_id) AS active_shelters,
       count(s.point) AS geocoded_shelters, max(s.collected_at) AS newest_collection
FROM data_source ds JOIN shelter s USING (source_id)
WHERE s.active GROUP BY ds.endpoint_key ORDER BY ds.endpoint_key;
SELECT ds.endpoint_key, max(r.finished_at) FILTER (WHERE r.status = 'SUCCESS') AS last_success,
       (array_agg(r.status ORDER BY r.started_at DESC, r.run_id DESC)
          FILTER (WHERE r.run_id IS NOT NULL))[1] AS latest_run_status
FROM data_source ds LEFT JOIN collection_run r USING (source_id)
GROUP BY ds.endpoint_key ORDER BY ds.endpoint_key;
\if :has_assignments
SELECT s.endpoint_key,s.active,a.contract_key,a.approved,a.legacy_workers_quiesced
FROM data_source s LEFT JOIN collection_source_assignment a
  ON a.source_id=s.source_id AND a.epoch=s.collection_assignment_epoch
ORDER BY s.endpoint_key;
\else
\echo COLLECTION_ASSIGNMENT_SCHEMA_MISSING
\endif
\if :has_vectors
SELECT count(*) AS vector_rows FROM rag_vector_embedding;
\else
\echo RAG_VECTOR_SCHEMA_MISSING
\endif
SELECT count(*) AS active_members_missing_notification_settings
FROM member m WHERE m.status = 'ACTIVE'
AND NOT EXISTS (SELECT 1 FROM notification_setting n WHERE n.member_id = m.member_id);

SELECT to_regclass('ai_resource_review') IS NOT NULL AS has_reviews,
       to_regclass('summary_runtime_task') IS NOT NULL AS has_summary_tasks,
       to_regclass('disaster_runtime_outbox') IS NOT NULL AS has_outbox
\gset
\if :has_reviews
SELECT count(*) AS unexpired_review_approvals
FROM ai_resource_review
WHERE approved_public_ai_evidence AND injection_reviewed AND revoked_at IS NULL
  AND reviewed_at <= now() AND valid_until > now();
\else
\echo AI_REVIEW_SCHEMA_MISSING
\endif
\if :has_summary_tasks
SELECT state, count(*) FROM summary_runtime_task GROUP BY state ORDER BY state;
\else
\echo SUMMARY_TASK_SCHEMA_MISSING
\endif
\if :has_outbox
SELECT state, count(*) FROM disaster_runtime_outbox GROUP BY state ORDER BY state;
\else
\echo OUTBOX_SCHEMA_MISSING
\endif
COMMIT;

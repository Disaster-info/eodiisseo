\set ON_ERROR_STOP on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '2s';

SELECT count(*) FILTER (WHERE active AND level = 'SIGUNGU') AS active_sigungu
FROM admin_area;
SELECT ds.endpoint_key, count(s.shelter_id) AS active_shelters,
       count(s.point) AS geocoded_shelters, max(s.collected_at) AS newest_collection
FROM data_source ds JOIN shelter s USING (source_id)
WHERE s.active GROUP BY ds.endpoint_key ORDER BY ds.endpoint_key;
SELECT ds.endpoint_key, max(r.finished_at) FILTER (WHERE r.status = 'SUCCESS') AS last_success,
       (array_agg(r.status ORDER BY r.started_at DESC, r.run_id DESC)
          FILTER (WHERE r.run_id IS NOT NULL))[1] AS latest_run_status
FROM data_source ds LEFT JOIN collection_run r USING (source_id)
GROUP BY ds.endpoint_key ORDER BY ds.endpoint_key;
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

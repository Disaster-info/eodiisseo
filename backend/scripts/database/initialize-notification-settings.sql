\set ON_ERROR_STOP on
\if :{?apply}
\else
\set apply false
\endif
BEGIN;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '2s';

\if :apply
WITH eligible AS MATERIALIZED (
    SELECT m.member_id FROM member m
    WHERE m.status = 'ACTIVE'
    AND NOT EXISTS (SELECT 1 FROM notification_setting n WHERE n.member_id = m.member_id)
    ORDER BY m.member_id
    FOR UPDATE OF m
), inserted AS (
    INSERT INTO notification_setting (member_id, enabled, minimum_severity_id, allow_after_22, updated_at)
    SELECT member_id, false, NULL, false, statement_timestamp() FROM eligible
    ON CONFLICT (member_id) DO NOTHING
    RETURNING 1
)
SELECT count(*) AS initialized_settings FROM inserted;
COMMIT;
\else
SELECT count(*) AS missing_active_settings FROM member m
WHERE m.status = 'ACTIVE'
AND NOT EXISTS (SELECT 1 FROM notification_setting n WHERE n.member_id = m.member_id);
ROLLBACK;
\endif

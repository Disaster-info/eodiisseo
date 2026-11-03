-- Follow-up to the preserved cf93adf migration; existing grant flags are not rewritten.
-- Grants without a verified granting session are denied by the application.
ALTER TABLE ai_external_consent ADD COLUMN granted_session_id uuid;
ALTER TABLE ai_external_consent ADD CONSTRAINT fk_ai_consent_granting_session
 FOREIGN KEY(granted_session_id) REFERENCES auth_session(session_id) ON DELETE SET NULL;

-- Holds only: no erasure, retention value, ACL grant, role or approval is seeded.
CREATE TABLE collection_production.archive_hold_audit (
 decision_id uuid PRIMARY KEY, attempt_id uuid NOT NULL REFERENCES public.collection_http_archive(attempt_id),
 actor_sha256 text NOT NULL CHECK(actor_sha256 ~ '^[a-f0-9]{64}$'),
 policy_artifact_id uuid REFERENCES public.collection_assurance_artifact(artifact_id),
 reason text NOT NULL CHECK(reason IN ('POLICY_REQUIRED','RETENTION_NOT_EXPIRED','REFERENCED','IMMUTABLE_BODY','ERASURE_NOT_APPROVED')),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON collection_production.archive_hold_audit FROM PUBLIC;
CREATE TRIGGER production_archive_hold_immutable BEFORE UPDATE OR DELETE ON collection_production.archive_hold_audit
 FOR EACH ROW EXECUTE FUNCTION public.collection_operations_append_only();
ALTER TABLE collection_production.archive_hold_audit ENABLE ALWAYS TRIGGER production_archive_hold_immutable;
CREATE FUNCTION collection_production.record_archive_hold(m uuid,s uuid,attempt uuid,decision uuid,reason text,policy uuid) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM collection_production.authorize_ops(m,s);
 INSERT INTO collection_production.archive_hold_audit(decision_id,attempt_id,actor_sha256,policy_artifact_id,reason)
 VALUES(decision,attempt,encode(sha256(convert_to(m::text,'UTF8')),'hex'),policy,reason);
 RETURN decision;
END $$;
REVOKE ALL ON FUNCTION collection_production.record_archive_hold(uuid,uuid,uuid,uuid,text,uuid) FROM PUBLIC;

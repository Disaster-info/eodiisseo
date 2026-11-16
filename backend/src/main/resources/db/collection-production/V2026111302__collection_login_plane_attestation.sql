-- Optional preparation only. No roles, credentials, grants or installation rows are seeded.
-- Reviewed bootstrap must transfer this function to the existing NOLOGIN Definer and grant
-- EXECUTE only to the three reviewed runtime roles. It is NOT cutover/execution authority.
CREATE FUNCTION collection_production.verify_login_plane(plane text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
 SELECT coalesce((SELECT session_user=CASE plane WHEN 'WORKER' THEN worker_role
 WHEN 'GATEWAY' THEN gateway_role WHEN 'OPS' THEN ops_role ELSE NULL END
 AND EXISTS(SELECT 1 FROM pg_roles WHERE rolname=session_user AND rolcanlogin)
 AND (SELECT count(*) FROM public.flyway_schema_history WHERE success AND version IN
 ('2026111001','2026111002','2026111003','2026111004','2026111005','2026111006','2026111301','2026111302'))=8
 AND NOT EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE NOT success)
 AND collection_production.privileges_valid() FROM collection_production.installation),false)
$$;
REVOKE ALL ON FUNCTION collection_production.verify_login_plane(text) FROM PUBLIC;

-- Reuse the existing ACTIVE OPS/member/session lock ordering without granting auth DML
-- (including writable primary keys) to an OPS runtime LOGIN.
CREATE FUNCTION collection_production.authorize_ops(m uuid,s uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 IF NOT collection_production.verify_login_plane('OPS') THEN RAISE EXCEPTION 'OPS LOGIN boundary rejected'; END IF;
 PERFORM collection_production.lock_ops(m,s);
END $$;
REVOKE ALL ON FUNCTION collection_production.authorize_ops(uuid,uuid) FROM PUBLIC;

-- Read-only fingerprint: binds this DB and exact principal, not a portable boolean approval.
CREATE FUNCTION collection_production.login_policy_hash(plane text,expected_worker name,expected_ops name) RETURNS text
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,collection_production,public SET TimeZone='UTC' AS $$
BEGIN
 IF NOT collection_production.verify_login_plane(plane) OR NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE worker_role=expected_worker AND ops_role=expected_ops) THEN RAISE EXCEPTION 'LOGIN boundary rejected'; END IF;
 RETURN encode(sha256(convert_to(collection_production.fingerprint()||':'||current_database()||':'||plane||':'||session_user,'UTF8')),'hex');
END $$;
REVOKE ALL ON FUNCTION collection_production.login_policy_hash(text,name,name) FROM PUBLIC;

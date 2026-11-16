-- Optional 4C-1. No role creation, grants to application roles, fleet stop, or production activation.
-- Apply only after 1001..1006 and owned-runtime. Configure principals separately under reviewed DDL.
CREATE SCHEMA collection_production;
REVOKE ALL ON SCHEMA collection_production FROM PUBLIC;
CREATE TABLE collection_production.installation (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 worker_role name NOT NULL, gateway_role name NOT NULL, ops_role name NOT NULL,
 legacy_roles name[] NOT NULL CHECK(cardinality(legacy_roles)>0),
 audit_only boolean NOT NULL DEFAULT false,
 CHECK(worker_role<>gateway_role AND worker_role<>ops_role AND gateway_role<>ops_role),
 CHECK(NOT audit_only OR (current_database() ~ '^collection4c_[a-f0-9]{32}$' AND inet_server_port()=55433))
);
CREATE TABLE collection_production.barrier (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 state text NOT NULL CHECK(state IN ('UNCONFIGURED','FROZEN','SEALED')),
 generation bigint NOT NULL CHECK(generation>=0), review_id uuid REFERENCES public.collection_transition_review,
 artifacts uuid[] NOT NULL DEFAULT '{}', policy_hash text, valid_until timestamptz,
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO collection_production.barrier(singleton,state,generation) VALUES(true,'UNCONFIGURED',0);
CREATE TABLE collection_production.worker (
 worker_id uuid PRIMARY KEY, generation bigint NOT NULL, sources uuid[] NOT NULL CHECK(cardinality(sources)>0),
 capability_hash text NOT NULL CHECK(capability_hash ~ '^[a-f0-9]{64}$'),
 member_id uuid NOT NULL REFERENCES public.member, session_id uuid NOT NULL,
 valid_until timestamptz NOT NULL, revoked_at timestamptz
);
CREATE TABLE collection_production.context (
 transaction_id xid8 NOT NULL, backend_pid integer NOT NULL, worker_id uuid NOT NULL REFERENCES collection_production.worker,
 source_id uuid NOT NULL REFERENCES public.data_source, phase text NOT NULL CHECK(phase IN ('CLAIM','EXECUTION','PROJECTION','RECEIPT')),
 run_id uuid, claim_id uuid, fence_token bigint, closed boolean NOT NULL DEFAULT false, opened_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(transaction_id,backend_pid)
);
CREATE TABLE collection_production.domain_touch (transaction_id xid8 NOT NULL,backend_pid integer NOT NULL,disaster_id uuid NOT NULL,PRIMARY KEY(transaction_id,backend_pid,disaster_id));
CREATE TABLE collection_production.history (
 decision_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),generation bigint NOT NULL,state text NOT NULL,
 member_id uuid NOT NULL REFERENCES public.member,session_id uuid NOT NULL,reference text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER production_history_immutable BEFORE UPDATE OR DELETE ON collection_production.history FOR EACH ROW EXECUTE FUNCTION public.collection_operations_append_only();
CREATE FUNCTION collection_production.fingerprint() RETURNS text LANGUAGE sql STABLE SET search_path=pg_catalog,collection_production,public AS $$
 SELECT encode(sha256(convert_to(jsonb_build_array(public.collection_policy_fingerprint(),
 (SELECT to_jsonb(i) FROM collection_production.installation i),
 (SELECT jsonb_agg(to_jsonb(a) ORDER BY artifact_id) FROM public.collection_assurance_artifact a),
 (SELECT jsonb_agg(to_jsonb(r) ORDER BY artifact_id) FROM public.collection_assurance_revocation r),
 (SELECT jsonb_agg(jsonb_build_array(rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls,rolinherit) ORDER BY rolname) FROM pg_roles WHERE rolname IN (SELECT worker_role FROM collection_production.installation UNION SELECT gateway_role FROM collection_production.installation UNION SELECT ops_role FROM collection_production.installation)),
 (SELECT jsonb_agg(jsonb_build_array(c.oid,c.relowner,c.relacl::text) ORDER BY c.oid) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','collection_production')),
 (SELECT jsonb_agg(jsonb_build_array(a.attrelid,a.attnum,a.attacl::text) ORDER BY a.attrelid,a.attnum) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','collection_production') AND a.attnum>0 AND NOT a.attisdropped),
 (SELECT jsonb_agg(jsonb_build_array(t.oid,t.tgenabled,pg_get_triggerdef(t.oid,true)) ORDER BY t.oid) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE c.relnamespace IN ('public'::regnamespace,'collection_production'::regnamespace) AND t.tgname LIKE 'production_%'),
 (SELECT jsonb_agg(jsonb_build_array(f.oid,f.proowner,f.proacl::text,f.proconfig::text,md5(pg_get_functiondef(f.oid))) ORDER BY f.oid) FROM pg_proc f JOIN pg_namespace n ON n.oid=f.pronamespace WHERE n.nspname='collection_production' AND f.prokind='f')
 )::text,'UTF8')),'hex')
$$;
CREATE FUNCTION collection_production.ops_valid(m uuid,s uuid) RETURNS boolean LANGUAGE sql VOLATILE SET search_path=pg_catalog,public AS $$
 SELECT EXISTS(SELECT 1 FROM public.auth_session a JOIN public.member b USING(member_id) JOIN public.member_role r USING(member_id)
 WHERE a.member_id=m AND a.session_id=s AND a.revoked_at IS NULL AND a.expires_at>clock_timestamp() AND b.status='ACTIVE' AND r.role='OPS')
$$;
CREATE FUNCTION collection_production.lock_ops(m uuid,s uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM member_id FROM public.member WHERE member_id=m FOR SHARE;
 PERFORM session_id FROM public.auth_session WHERE member_id=m AND session_id=s FOR SHARE;
 PERFORM member_id FROM public.member_role WHERE member_id=m AND role='OPS' FOR SHARE;
 IF NOT collection_production.ops_valid(m,s) THEN RAISE EXCEPTION 'Active OPS authority required'; END IF;
END $$;
CREATE FUNCTION collection_production.privileges_valid() RETURNS boolean LANGUAGE plpgsql STABLE SET search_path=pg_catalog,collection_production,public AS $$
DECLARE i collection_production.installation; principal name; t record; f record;
BEGIN
 SELECT * INTO i FROM collection_production.installation; IF NOT FOUND THEN RETURN false; END IF;
 IF i.audit_only AND (current_database() !~ '^collection4c_[a-f0-9]{32}$' OR coalesce(inet_server_port(),0)<>55433) THEN RETURN false; END IF;
 FOREACH principal IN ARRAY ARRAY[i.worker_role,i.gateway_role,i.ops_role]||i.legacy_roles LOOP
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=principal AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit) THEN RETURN false; END IF;
  IF has_parameter_privilege(principal,'session_replication_role','SET') THEN RETURN false; END IF;
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname<>principal AND pg_has_role(principal,oid,'MEMBER')) OR has_database_privilege(principal,current_database(),'CREATE') OR has_database_privilege(principal,current_database(),'TEMP') OR has_schema_privilege(principal,'public','CREATE') OR has_schema_privilege(principal,'collection_production','CREATE') THEN RETURN false; END IF;
  IF EXISTS(SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid=c.relowner WHERE r.rolname=principal AND c.relnamespace IN ('public'::regnamespace,'collection_production'::regnamespace)) THEN RETURN false; END IF;
 END LOOP;
 FOR t IN SELECT c.oid,c.relnamespace FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','collection_production') AND c.relkind IN ('r','p','v') LOOP
  IF has_table_privilege(i.gateway_role,t.oid,'TRUNCATE,TRIGGER') OR has_table_privilege(i.ops_role,t.oid,'TRUNCATE,TRIGGER') THEN RETURN false; END IF;
  IF t.relnamespace='collection_production'::regnamespace AND (has_table_privilege(i.gateway_role,t.oid,'DELETE') OR has_any_column_privilege(i.gateway_role,t.oid,'INSERT,UPDATE') OR has_table_privilege(i.ops_role,t.oid,'DELETE') OR has_any_column_privilege(i.ops_role,t.oid,'INSERT,UPDATE')) THEN RETURN false; END IF;
  IF has_table_privilege(i.worker_role,t.oid,'DELETE,TRUNCATE,TRIGGER') OR has_any_column_privilege(i.worker_role,t.oid,'INSERT,UPDATE,REFERENCES') THEN RETURN false; END IF;
 END LOOP;
 FOR f IN SELECT p.proowner,p.oid FROM pg_proc p WHERE p.pronamespace='collection_production'::regnamespace AND p.prosecdef LOOP
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE oid=f.proowner AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolbypassrls)
  OR EXISTS(SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE p.oid=f.oid AND a.grantee=0 AND a.privilege_type='EXECUTE') THEN RETURN false; END IF;
 END LOOP;
 IF (SELECT count(*) FROM pg_trigger trg JOIN pg_class rel ON rel.oid=trg.tgrelid WHERE rel.relnamespace='public'::regnamespace AND trg.tgfoid='collection_production.write_guard()'::regprocedure AND trg.tgenabled='A')<>27 THEN RETURN false; END IF;
 RETURN true;
END $$;
CREATE FUNCTION collection_production.proofs_valid() RETURNS boolean LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,collection_production,public AS $$
DECLARE b collection_production.barrier; i collection_production.installation;
BEGIN
 SELECT * INTO i FROM collection_production.installation; SELECT * INTO b FROM collection_production.barrier;
 RETURN coalesce(collection_production.privileges_valid() AND b.state='SEALED' AND b.valid_until>clock_timestamp() AND b.policy_hash=collection_production.fingerprint()
 AND cardinality(b.artifacts)>=5 AND NOT EXISTS(SELECT 1 FROM unnest(b.artifacts) id LEFT JOIN public.collection_assurance_artifact a ON a.artifact_id=id
 WHERE a.artifact_id IS NULL OR a.valid_from>clock_timestamp() OR a.valid_until<=clock_timestamp() OR a.synthetic<>i.audit_only
 OR EXISTS(SELECT 1 FROM public.collection_assurance_revocation r WHERE r.artifact_id=id))
 AND (SELECT count(DISTINCT kind) FROM public.collection_assurance_artifact WHERE artifact_id=ANY(b.artifacts) AND kind IN ('FLEET','SENDER','DATABASE','ARCHIVE','DOMAIN_POLICY'))=5
 AND EXISTS(SELECT 1 FROM public.collection_transition_review m JOIN public.collection_transition_review d ON d.review_id=m.parent_review_id AND d.step='DRAIN_VERIFIED'
 JOIN public.collection_transition_review s ON s.review_id=d.parent_review_id AND s.step='STOP_VERIFIED'
 WHERE m.review_id=b.review_id AND m.step='MIGRATIONS_VERIFIED' AND least(m.valid_until,d.valid_until,s.valid_until)>clock_timestamp()
 AND m.synthetic=i.audit_only AND d.synthetic=i.audit_only AND s.synthetic=i.audit_only),false);
END $$;
CREATE FUNCTION collection_production.freeze(m uuid,s uuid,ref text) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(78161108);PERFORM collection_production.lock_ops(m,s);
 IF NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE ops_role=session_user) OR NOT collection_production.ops_valid(m,s) OR ref !~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$' THEN RAISE EXCEPTION 'Production control denied'; END IF;
 UPDATE collection_production.barrier SET state='FROZEN',generation=generation+1,valid_until=NULL,changed_at=clock_timestamp();
 INSERT INTO collection_production.history(generation,state,member_id,session_id,reference) SELECT generation,state,m,s,ref FROM collection_production.barrier;
END $$;
CREATE FUNCTION collection_production.seal(m uuid,s uuid,review uuid,evidence uuid[],until_at timestamptz,ref text) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(78161108);PERFORM collection_production.lock_ops(m,s); PERFORM 1 FROM collection_production.barrier FOR UPDATE;
 IF NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE ops_role=session_user) OR NOT collection_production.ops_valid(m,s)
 OR until_at<=clock_timestamp() OR ref !~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$' THEN RAISE EXCEPTION 'Production control denied'; END IF;
 IF NOT (SELECT audit_only FROM collection_production.installation) AND EXISTS(SELECT 1 FROM public.collection_source_assignment WHERE approved AND contract_key IN ('KMA_PORTAL_EQK_MSG','KMA_HUB_EQK_MSG','KMA_WARNING_LIST','KMA_RAIN_CODES','MOIS_MESSAGES','MOIS_SHELTERS','MOIS_WILDFIRE')) THEN RAISE EXCEPTION 'Official contracts remain blocked'; END IF;
 IF (SELECT state FROM collection_production.barrier)<>'FROZEN' OR (SELECT state FROM public.collection_cutover_barrier)<>'LEGACY'
 OR EXISTS(SELECT 1 FROM public.collection_run WHERE status='RUNNING') OR EXISTS(SELECT 1 FROM public.collection_job_lock)
 OR EXISTS(SELECT 1 FROM public.collection_target_lease WHERE claim_id IS NOT NULL)
 OR EXISTS(SELECT 1 FROM public.collection_http_attempt WHERE state IN ('IN_FLIGHT','RECEIVED','UNKNOWN')) THEN RAISE EXCEPTION 'Drain required'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE version='2026111301' AND success) THEN RAISE EXCEPTION 'Production migration required'; END IF;
 UPDATE collection_production.barrier SET state='SEALED',generation=generation+1,review_id=review,artifacts=evidence,valid_until=until_at,policy_hash=collection_production.fingerprint(),changed_at=clock_timestamp();
 IF NOT collection_production.proofs_valid() THEN RAISE EXCEPTION 'Reviewed production proofs required'; END IF;
 INSERT INTO collection_production.history(generation,state,member_id,session_id,reference) SELECT generation,state,m,s,ref FROM collection_production.barrier;
END $$;
CREATE FUNCTION collection_production.issue_worker(m uuid,s uuid,id uuid,sources uuid[],cap_hash text,until_at timestamptz) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(78161108);PERFORM collection_production.lock_ops(m,s);
 IF NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE ops_role=session_user) OR NOT collection_production.ops_valid(m,s)
 OR NOT collection_production.proofs_valid() OR until_at<=clock_timestamp() OR until_at>(SELECT valid_until FROM collection_production.barrier)
 OR cardinality(sources)<1 OR cap_hash !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'Worker issuance denied'; END IF;
 IF EXISTS(SELECT 1 FROM unnest(sources) x LEFT JOIN public.data_source d ON d.source_id=x LEFT JOIN public.collection_source_assignment a ON a.source_id=d.source_id AND a.epoch=d.collection_assignment_epoch WHERE NOT coalesce(d.active AND a.approved AND a.legacy_workers_quiesced,false)) THEN RAISE EXCEPTION 'Assignment required'; END IF;
 INSERT INTO collection_production.worker SELECT id,generation,sources,cap_hash,m,s,until_at,NULL FROM collection_production.barrier;
END $$;
CREATE FUNCTION collection_production.assert_context(require_closed boolean DEFAULT false) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
DECLARE c collection_production.context; w collection_production.worker; r public.collection_run;
BEGIN
 SELECT * INTO c FROM collection_production.context WHERE transaction_id=pg_current_xact_id() AND backend_pid=pg_backend_pid();
 SELECT * INTO w FROM collection_production.worker WHERE worker_id=c.worker_id;
 IF current_setting('session_replication_role')<>'origin' OR c.worker_id IS NULL OR (require_closed AND NOT c.closed) OR w.revoked_at IS NOT NULL OR w.valid_until<=clock_timestamp()
 OR NOT collection_production.ops_valid(w.member_id,w.session_id) OR NOT collection_production.proofs_valid()
 OR w.generation<>(SELECT generation FROM collection_production.barrier) OR NOT c.source_id=ANY(w.sources)
 OR NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE gateway_role=session_user) THEN RAISE EXCEPTION 'Stale production context'; END IF;
 IF c.phase<>'CLAIM' OR (require_closed AND c.run_id IS NOT NULL) THEN
  SELECT * INTO r FROM public.collection_run WHERE run_id=c.run_id;
  IF r.run_id IS NULL OR r.claim_id IS DISTINCT FROM c.claim_id OR r.fence_token IS DISTINCT FROM c.fence_token
  OR r.source_id<>c.source_id OR r.assignment_epoch IS DISTINCT FROM (SELECT collection_assignment_epoch FROM public.data_source WHERE source_id=c.source_id)
  OR NOT EXISTS(SELECT 1 FROM public.collection_source_assignment a WHERE a.source_id=r.source_id AND a.epoch=r.assignment_epoch AND a.approved AND a.legacy_workers_quiesced)
  THEN RAISE EXCEPTION 'Production fence rejected'; END IF;
  IF r.status<>'RUNNING' AND (c.phase<>'EXECUTION' OR r.finished_at IS NULL OR r.finished_at<c.opened_at) THEN RAISE EXCEPTION 'Execution was not completed by this gateway transaction'; END IF;
  IF r.status='RUNNING' AND (r.status<>'RUNNING' OR NOT EXISTS(SELECT 1 FROM public.collection_target_lease l WHERE l.run_id=r.run_id AND l.claim_id=r.claim_id AND l.fence_token=r.fence_token AND l.lease_until>clock_timestamp())
  OR NOT EXISTS(SELECT 1 FROM public.collection_job_lock l WHERE l.run_id=r.run_id AND l.lease_until>clock_timestamp())) THEN RAISE EXCEPTION 'Production lease rejected'; END IF;
 END IF;
END $$;
CREATE FUNCTION collection_production.open_context(id uuid,capability text,source uuid,phase_name text,run uuid,claim uuid,fence bigint) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(78161108);
 IF NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE gateway_role=session_user) OR NOT EXISTS(SELECT 1 FROM collection_production.worker WHERE worker_id=id AND capability_hash=encode(sha256(convert_to(capability,'UTF8')),'hex')) THEN RAISE EXCEPTION 'Gateway principal or capability rejected'; END IF;
 PERFORM collection_production.lock_ops(w.member_id,w.session_id) FROM collection_production.worker w WHERE w.worker_id=id;
 IF phase_name<>'CLAIM' AND NOT EXISTS(SELECT 1 FROM public.collection_run WHERE run_id=run AND status='RUNNING') THEN RAISE EXCEPTION 'Live execution required at gateway entry'; END IF;
 INSERT INTO collection_production.context VALUES(pg_current_xact_id(),pg_backend_pid(),id,source,phase_name,run,claim,fence,false,clock_timestamp());
 PERFORM collection_production.assert_context();
END $$;
-- Validate wall clock at flush/commit, then seal this transaction context.
CREATE FUNCTION collection_production.close_context() RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 UPDATE collection_production.context SET closed=true WHERE transaction_id=pg_current_xact_id() AND backend_pid=pg_backend_pid() AND NOT closed;
 IF NOT FOUND THEN RAISE EXCEPTION 'Production context missing'; END IF;
 PERFORM collection_production.assert_context(true);
END $$;
CREATE FUNCTION collection_production.commit_guard() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
DECLARE c collection_production.context;
BEGIN
 PERFORM collection_production.assert_context(true);
 SELECT * INTO c FROM collection_production.context WHERE transaction_id=pg_current_xact_id() AND backend_pid=pg_backend_pid();
 IF EXISTS(SELECT 1 FROM collection_production.domain_touch t WHERE t.transaction_id=c.transaction_id AND t.backend_pid=c.backend_pid
 AND NOT EXISTS(SELECT 1 FROM public.source_disaster d WHERE d.disaster_id=t.disaster_id AND (d.source_id=c.source_id OR EXISTS(SELECT 1 FROM public.collection_operation_approval a WHERE a.source_id=c.source_id AND a.canonical_source_id=d.source_id AND a.valid_until>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM public.collection_approval_revocation v WHERE v.approval_id=a.approval_id))))) THEN RAISE EXCEPTION 'Projection left unscoped domain writes'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER production_context_commit AFTER INSERT ON collection_production.context DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION collection_production.commit_guard();
CREATE FUNCTION collection_production.write_guard() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
DECLARE i collection_production.installation; c collection_production.context; n jsonb; o jsonb; source uuid; id uuid;
BEGIN
 SELECT * INTO i FROM collection_production.installation;
 IF NOT FOUND THEN IF EXISTS(SELECT 1 FROM collection_production.history) OR coalesce((SELECT state FROM collection_production.barrier),'MISSING')<>'UNCONFIGURED' THEN RAISE EXCEPTION 'Production policy lost; legacy execution cannot resume'; END IF; IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END IF;
 -- Domain consumers retain their existing privileges. All inventoried legacy producers must be listed.
 IF TG_ARGV[0] IN ('DOMAIN','POLICY') AND session_user<>i.gateway_role AND session_user<>i.worker_role AND NOT session_user=ANY(i.legacy_roles) THEN IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END IF;
 IF TG_ARGV[0]='POLICY' THEN RAISE EXCEPTION 'Gateway cannot edit approval policy'; END IF;
  IF TG_OP<>'DELETE' THEN n=to_jsonb(NEW); END IF; IF TG_OP<>'INSERT' THEN o=to_jsonb(OLD); END IF;
 id=coalesce((n->>'run_id')::uuid,(o->>'run_id')::uuid);
 IF session_user=i.ops_role AND id IS NOT NULL AND public.collection_resolution_context_valid(id) THEN
  IF TG_TABLE_NAME='collection_run' AND TG_OP='UPDATE' AND n->>'status'='FAILED' AND (n-'status'-'finished_at'-'error_code')=(o-'status'-'finished_at'-'error_code') THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='collection_job_lock' AND TG_OP='DELETE' THEN RETURN OLD; END IF;
  IF TG_TABLE_NAME='collection_target_lease' AND TG_OP='UPDATE' AND n->>'run_id' IS NULL AND n->>'claim_id' IS NULL AND n->>'lease_until' IS NULL AND (n-'run_id'-'claim_id'-'lease_until')=(o-'run_id'-'claim_id'-'lease_until') THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='collection_http_attempt' AND TG_OP='UPDATE' AND n->>'state'='REJECTED' AND (n-'state'-'finished_at')=(o-'state'-'finished_at') THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='collection_http_execution' AND TG_OP='UPDATE' AND n->>'state'='FAILED' AND (n-'state'-'reason')=(o-'state'-'reason') THEN RETURN NEW; END IF;
  RAISE EXCEPTION 'Resolution cannot rewrite provenance';
 END IF;
 PERFORM collection_production.assert_context();
 SELECT * INTO c FROM collection_production.context WHERE transaction_id=pg_current_xact_id() AND backend_pid=pg_backend_pid();
 IF c.closed THEN RAISE EXCEPTION 'Closed gateway transaction'; END IF;
 IF c.run_id IS NOT NULL AND (SELECT status FROM public.collection_run WHERE run_id=c.run_id)<>'RUNNING' AND TG_TABLE_NAME NOT IN ('collection_job_lock','collection_target_lease') THEN RAISE EXCEPTION 'Only completion cleanup remains permitted'; END IF;
 IF TG_OP<>'DELETE' THEN n=to_jsonb(NEW); END IF; IF TG_OP<>'INSERT' THEN o=to_jsonb(OLD); END IF;
 IF c.phase='CLAIM' AND (TG_TABLE_NAME NOT IN ('collection_run','collection_target_lease','collection_job_lock') OR (TG_TABLE_NAME IN ('collection_run','collection_job_lock') AND TG_OP<>'INSERT')) THEN RAISE EXCEPTION 'Claim mutation rejected'; END IF;
 IF TG_ARGV[0]='OWNED' THEN RAISE EXCEPTION 'Legacy owned collector is frozen'; END IF;
 IF TG_ARGV[0]='DOMAIN' AND c.phase<>'PROJECTION' THEN RAISE EXCEPTION 'Projection scope required'; END IF;
 source=coalesce((n->>'source_id')::uuid,(o->>'source_id')::uuid);
 IF source IS NOT NULL AND source<>c.source_id AND NOT EXISTS(SELECT 1 FROM public.collection_operation_approval a WHERE a.source_id=c.source_id AND a.canonical_source_id=source AND a.valid_until>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM public.collection_approval_revocation v WHERE v.approval_id=a.approval_id)) THEN RAISE EXCEPTION 'Source scope rejected'; END IF;
  IF (o->>'source_id') IS NOT NULL AND (o->>'source_id')::uuid<>c.source_id AND NOT EXISTS(SELECT 1 FROM public.collection_operation_approval a WHERE a.source_id=c.source_id AND a.canonical_source_id=(o->>'source_id')::uuid AND a.valid_until>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM public.collection_approval_revocation v WHERE v.approval_id=a.approval_id)) THEN RAISE EXCEPTION 'Previous source scope rejected'; END IF;
 IF TG_ARGV[0]='DOMAIN' THEN
  id=coalesce((n->>'disaster_id')::uuid,(o->>'disaster_id')::uuid);
  IF id IS NULL AND coalesce(n,o)->>'source_disaster_id' IS NOT NULL THEN SELECT disaster_id INTO id FROM public.source_disaster WHERE source_disaster_id=(coalesce(n,o)->>'source_disaster_id')::uuid; END IF;
  IF id IS NOT NULL THEN
   IF NOT (TG_TABLE_NAME='disaster' AND TG_OP='INSERT') AND TG_TABLE_NAME<>'source_disaster' AND NOT EXISTS(SELECT 1 FROM public.source_disaster d WHERE d.disaster_id=id AND (d.source_id=c.source_id OR EXISTS(SELECT 1 FROM public.collection_operation_approval a WHERE a.source_id=c.source_id AND a.canonical_source_id=d.source_id AND a.valid_until>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM public.collection_approval_revocation v WHERE v.approval_id=a.approval_id)))) THEN RAISE EXCEPTION 'Disaster scope rejected'; END IF;
   IF TG_TABLE_NAME='source_disaster' AND EXISTS(SELECT 1 FROM public.source_disaster d WHERE d.disaster_id=id AND d.source_id IS DISTINCT FROM source) THEN RAISE EXCEPTION 'Cross-source disaster association rejected'; END IF;
   INSERT INTO collection_production.domain_touch VALUES(c.transaction_id,c.backend_pid,id) ON CONFLICT DO NOTHING;
  END IF;
 END IF;
  IF TG_TABLE_NAME='collection_http_archive' THEN
  SELECT run_id INTO id FROM public.collection_http_attempt WHERE attempt_id=(coalesce(n,o)->>'attempt_id')::uuid;
  IF id IS DISTINCT FROM c.run_id THEN RAISE EXCEPTION 'Archive attempt scope rejected'; END IF;
 END IF;
 IF TG_TABLE_NAME='collection_raw_revision' THEN
  SELECT source_id INTO source FROM public.collection_raw_record WHERE record_id=(coalesce(n,o)->>'record_id')::uuid;
  IF source IS DISTINCT FROM c.source_id AND NOT EXISTS(SELECT 1 FROM public.collection_operation_approval a WHERE a.source_id=c.source_id AND a.canonical_source_id=source AND a.valid_until>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM public.collection_approval_revocation v WHERE v.approval_id=a.approval_id)) THEN RAISE EXCEPTION 'Raw revision scope rejected'; END IF;
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Raw revision history is immutable'; END IF;
 END IF;
 IF TG_TABLE_NAME='collection_quota_window' THEN
  IF TG_OP<>'UPDATE' OR (n-'reserved'-'next_request_at') IS DISTINCT FROM (o-'reserved'-'next_request_at')
  OR n->>'quota_scope_id' IS DISTINCT FROM (SELECT a.quota_scope_id FROM public.collection_source_assignment a JOIN public.data_source s ON s.source_id=a.source_id AND s.collection_assignment_epoch=a.epoch WHERE s.source_id=c.source_id) THEN RAISE EXCEPTION 'Quota reservation scope rejected'; END IF;
 END IF;
 IF TG_TABLE_NAME='collection_run' AND TG_OP='INSERT' THEN
  IF c.phase<>'CLAIM' OR source<>c.source_id OR n->>'execution_owner'<>'COMMON_COLLECTION_V1' OR (n->>'assignment_epoch')::bigint IS DISTINCT FROM (SELECT collection_assignment_epoch FROM public.data_source WHERE source_id=NEW.source_id) THEN RAISE EXCEPTION 'Claim scope rejected'; END IF;
  UPDATE collection_production.context SET run_id=(n->>'run_id')::uuid,claim_id=(n->>'claim_id')::uuid,fence_token=(n->>'fence_token')::bigint WHERE transaction_id=c.transaction_id AND backend_pid=c.backend_pid;
 END IF;
 id=coalesce((n->>'run_id')::uuid,(o->>'run_id')::uuid);
 IF id IS NOT NULL AND c.phase<>'CLAIM' AND id IS DISTINCT FROM c.run_id THEN RAISE EXCEPTION 'Run scope rejected'; END IF;
 IF TG_TABLE_NAME='collection_target_lease' AND coalesce((n->>'target_id')::uuid,(o->>'target_id')::uuid) IS DISTINCT FROM (SELECT a.target_id FROM public.collection_source_assignment a JOIN public.data_source s ON s.source_id=a.source_id AND s.collection_assignment_epoch=a.epoch WHERE s.source_id=c.source_id) THEN RAISE EXCEPTION 'Target scope rejected'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['collection_run','collection_job_lock','collection_target_lease','collection_raw_record','collection_raw_revision','collection_http_execution','collection_http_attempt','collection_http_archive','collection_quota_window','collection_domain_cursor','collection_domain_coverage','collection_projection_receipt'] LOOP
 EXECUTE format('CREATE TRIGGER production_write_guard BEFORE INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION collection_production.write_guard(%L)',t,'EXECUTION'); EXECUTE format('ALTER TABLE public.%I ENABLE ALWAYS TRIGGER production_write_guard',t); END LOOP;
 FOREACH t IN ARRAY ARRAY['data_source','collection_source_assignment','collection_contract','collection_target'] LOOP
 EXECUTE format('CREATE TRIGGER production_policy_guard BEFORE INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION collection_production.write_guard(%L)',t,'POLICY'); EXECUTE format('ALTER TABLE public.%I ENABLE ALWAYS TRIGGER production_policy_guard',t); END LOOP;
 FOREACH t IN ARRAY ARRAY['owned_collection_cursor','owned_collection_page','owned_collection_item','owned_collection_coverage'] LOOP
 EXECUTE format('CREATE TRIGGER production_write_guard BEFORE INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION collection_production.write_guard(%L)',t,'OWNED'); EXECUTE format('ALTER TABLE public.%I ENABLE ALWAYS TRIGGER production_write_guard',t); END LOOP;
 FOREACH t IN ARRAY ARRAY['disaster','source_disaster','source_disaster_revision','disaster_change','disaster_area','owned_incident_head','disaster_runtime_outbox'] LOOP
 EXECUTE format('CREATE TRIGGER production_write_guard BEFORE INSERT OR UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION collection_production.write_guard(%L)',t,'DOMAIN'); EXECUTE format('ALTER TABLE public.%I ENABLE ALWAYS TRIGGER production_write_guard',t); END LOOP;
END $$;
CREATE FUNCTION collection_production.revoke_worker(m uuid,s uuid,id uuid,ref text) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(78161108);PERFORM collection_production.lock_ops(m,s);
 IF NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE ops_role=session_user) OR NOT collection_production.ops_valid(m,s) OR ref !~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$' THEN RAISE EXCEPTION 'Production control denied'; END IF;
 UPDATE collection_production.worker SET revoked_at=clock_timestamp() WHERE worker_id=id AND revoked_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Worker already revoked or missing'; END IF;
 INSERT INTO collection_production.history(generation,state,member_id,session_id,reference) SELECT generation,'WORKER_REVOKED',m,s,ref FROM collection_production.barrier;
END $$;
CREATE FUNCTION collection_production.status() RETURNS text LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM collection_production.installation) THEN 'PRINCIPALS_UNCONFIGURED'
 WHEN NOT collection_production.privileges_valid() THEN 'PRODUCTION_PRIVILEGES_NOT_REVIEWED' WHEN NOT collection_production.proofs_valid() THEN 'PRODUCTION_BARRIER_NOT_SEALED_OR_PROOFS_INVALID'
 ELSE 'SEALED_NOT_EXECUTION_AUTHORIZATION' END
$$;
CREATE FUNCTION collection_production.resolve_held(m uuid,s uuid,resolution uuid,completion uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
DECLARE r public.collection_run_resolution; d public.collection_resolution_detail; i collection_production.installation; a public.collection_assurance_artifact;
BEGIN
 PERFORM pg_advisory_xact_lock(78161108);
 PERFORM collection_production.lock_ops(m,s);
 SELECT * INTO i FROM collection_production.installation;SELECT * INTO r FROM public.collection_run_resolution WHERE resolution_id=resolution;SELECT * INTO d FROM public.collection_resolution_detail WHERE resolution_id=resolution;SELECT * INTO a FROM public.collection_assurance_artifact WHERE artifact_id=completion;
 IF i.singleton IS NULL OR r.resolution_id IS NULL OR d.resolution_id IS NULL OR a.artifact_id IS NULL OR session_user<>i.ops_role OR NOT collection_production.ops_valid(m,s) OR (SELECT state FROM collection_production.barrier)<>'FROZEN'
 OR r.reviewed_by IS DISTINCT FROM m OR d.session_id IS DISTINCT FROM s OR r.decision<>'RELEASE_CONFIRMED_STOPPED'
 OR a.kind<>'HTTP_COMPLETION' OR a.valid_from>clock_timestamp() OR a.valid_until<=clock_timestamp() OR a.synthetic<>i.audit_only
 OR a.payload->>'run_id' IS DISTINCT FROM r.run_id::text OR a.payload->>'snapshot_sha256' IS DISTINCT FROM d.expected_snapshot
 OR a.payload->>'no_active_request' IS DISTINCT FROM 'true' OR a.reference IS DISTINCT FROM d.completion_reference
 OR EXISTS(SELECT 1 FROM public.collection_assurance_revocation WHERE artifact_id=a.artifact_id) OR NOT EXISTS(SELECT 1 FROM public.collection_transition_review WHERE review_id=d.stop_review_id AND step='STOP_VERIFIED' AND valid_until>clock_timestamp() AND synthetic=i.audit_only) THEN RAISE EXCEPTION 'Exact reviewed completion required'; END IF;
 PERFORM set_config('eodiisseo.collection.ops_member',m::text,true);PERFORM set_config('eodiisseo.collection.ops_session',s::text,true);PERFORM set_config('eodiisseo.collection.resolution',resolution::text,true);
 UPDATE public.collection_run SET status='FAILED',finished_at=clock_timestamp(),error_code='OPS_CONFIRMED_STOPPED' WHERE run_id=r.run_id AND status='RUNNING';
 IF NOT FOUND THEN RAISE EXCEPTION 'Resolution already applied or run missing'; END IF;
 DELETE FROM public.collection_job_lock WHERE run_id=r.run_id;
 UPDATE public.collection_target_lease SET run_id=NULL,claim_id=NULL,lease_until=NULL WHERE run_id=r.run_id;
 UPDATE public.collection_http_attempt SET state='REJECTED',finished_at=clock_timestamp() WHERE run_id=r.run_id AND state IN ('IN_FLIGHT','RECEIVED','UNKNOWN');
 UPDATE public.collection_http_execution SET state='FAILED',reason='OPS_CONFIRMED_STOPPED' WHERE run_id=r.run_id;
 INSERT INTO collection_production.history(generation,state,member_id,session_id,reference) SELECT generation,'RESOLVED_FAILED',m,s,r.evidence_reference FROM collection_production.barrier;
END $$;
ALTER TABLE collection_production.context ENABLE ALWAYS TRIGGER production_context_commit;
ALTER TABLE collection_production.history ENABLE ALWAYS TRIGGER production_history_immutable;
REVOKE ALL ON ALL TABLES IN SCHEMA collection_production FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA collection_production FROM PUBLIC;
-- No installation row is seeded. No application role gains DML/EXECUTE automatically.
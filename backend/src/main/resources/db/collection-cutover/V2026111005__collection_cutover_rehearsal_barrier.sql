-- Optional, additive 4B-1 protocol. Default LEGACY is inert. No production arming path.
CREATE TABLE collection_cutover_barrier (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 state varchar(20) NOT NULL CHECK(state IN ('LEGACY','AUDIT_UNIFIED','FROZEN')),
 generation bigint NOT NULL CHECK(generation>=0),
 review_id uuid REFERENCES collection_transition_review(review_id),
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO collection_cutover_barrier VALUES(true,'LEGACY',0,null,clock_timestamp());
CREATE TABLE collection_worker_session (
 worker_session_id uuid PRIMARY KEY,
 generation bigint NOT NULL UNIQUE CHECK(generation>0),
 sources uuid[] NOT NULL CHECK(cardinality(sources)>0),
 policy_fingerprint char(64) NOT NULL CHECK(policy_fingerprint ~ '^[a-f0-9]{64}$'),
 local_fingerprint char(64) NOT NULL CHECK(local_fingerprint ~ '^[a-f0-9]{64}$'),
 ops_member_id uuid NOT NULL REFERENCES member(member_id), ops_session_id uuid NOT NULL,
 valid_until timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 synthetic boolean NOT NULL CHECK(synthetic), CHECK(valid_until>created_at)
);
CREATE TABLE collection_run_worker (
 run_id uuid PRIMARY KEY REFERENCES collection_run(run_id),
 worker_session_id uuid NOT NULL REFERENCES collection_worker_session(worker_session_id),
 claim_id uuid NOT NULL UNIQUE, fence_token bigint NOT NULL CHECK(fence_token>0)
);
CREATE TABLE collection_cutover_history (
 decision_id uuid PRIMARY KEY, generation bigint NOT NULL UNIQUE,
 state varchar(20) NOT NULL CHECK(state IN ('AUDIT_UNIFIED','FROZEN')),
 reviewed_by uuid NOT NULL REFERENCES member(member_id), session_id uuid NOT NULL,
 evidence_reference varchar(160) NOT NULL CHECK(evidence_reference ~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER collection_worker_immutable BEFORE UPDATE OR DELETE ON collection_worker_session FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
CREATE TRIGGER collection_run_worker_immutable BEFORE UPDATE OR DELETE ON collection_run_worker FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
CREATE TRIGGER collection_cutover_history_immutable BEFORE UPDATE OR DELETE ON collection_cutover_history FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
CREATE FUNCTION collection_policy_fingerprint() RETURNS text LANGUAGE sql STABLE SET TimeZone='UTC' AS $$
 SELECT encode(sha256(convert_to(jsonb_build_array(
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY source_id) FROM data_source t),
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY source_id,epoch) FROM collection_source_assignment t),
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY contract_key) FROM collection_contract t),
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY target_id) FROM collection_target t),
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY approval_id) FROM collection_operation_approval t),
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY approval_id) FROM collection_component_approval t),
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY approval_id) FROM collection_approval_revocation t),
  (SELECT jsonb_agg(to_jsonb(t) ORDER BY review_id) FROM collection_transition_review t),
  (SELECT jsonb_agg(jsonb_build_array(quota_scope_id,starts_at,ends_at,request_limit,min_interval_ms,approval_reference) ORDER BY quota_scope_id,starts_at) FROM collection_quota_window)
 )::text,'UTF8')),'hex')
$$;
CREATE FUNCTION collection_ops_context_valid() RETURNS boolean LANGUAGE sql VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM auth_session s JOIN member m USING(member_id) JOIN member_role r USING(member_id)
 WHERE s.session_id::text=current_setting('eodiisseo.collection.ops_session',true)
 AND s.member_id::text=current_setting('eodiisseo.collection.ops_member',true)
 AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp() AND m.status='ACTIVE' AND r.role='OPS')
$$;
CREATE FUNCTION collection_worker_context_valid() RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$
DECLARE b collection_cutover_barrier; w collection_worker_session;
BEGIN
 SELECT * INTO b FROM collection_cutover_barrier WHERE singleton FOR SHARE;
 SELECT * INTO w FROM collection_worker_session WHERE worker_session_id::text=current_setting('eodiisseo.collection.worker',true);
 RETURN coalesce(b.state='AUDIT_UNIFIED' AND w.worker_session_id IS NOT NULL AND w.generation=b.generation
 AND w.valid_until>clock_timestamp() AND w.policy_fingerprint=collection_policy_fingerprint()
 AND EXISTS(SELECT 1 FROM auth_session s JOIN member m USING(member_id) JOIN member_role r USING(member_id)
 WHERE s.session_id=w.ops_session_id AND s.member_id=w.ops_member_id AND s.revoked_at IS NULL
 AND s.expires_at>clock_timestamp() AND m.status='ACTIVE' AND r.role='OPS'),false);
END $$;
CREATE FUNCTION collection_run_context_valid(id uuid,live boolean) RETURNS boolean LANGUAGE sql VOLATILE AS $$
 SELECT collection_worker_context_valid() AND EXISTS(
 SELECT 1 FROM collection_run_worker k JOIN collection_run r USING(run_id)
 JOIN collection_worker_session w USING(worker_session_id)
 WHERE k.run_id=id AND w.worker_session_id::text=current_setting('eodiisseo.collection.worker',true)
 AND r.run_id::text=current_setting('eodiisseo.collection.run',true)
 AND r.claim_id::text=current_setting('eodiisseo.collection.claim',true)
 AND r.fence_token::text=current_setting('eodiisseo.collection.fence',true)
 AND k.claim_id=r.claim_id AND k.fence_token=r.fence_token AND r.source_id=ANY(w.sources)
 AND (NOT live OR (r.status='RUNNING' AND EXISTS(SELECT 1 FROM collection_target_lease l
 WHERE l.run_id=r.run_id AND l.claim_id=r.claim_id AND l.fence_token=r.fence_token AND l.lease_until>clock_timestamp())
 AND EXISTS(SELECT 1 FROM collection_job_lock l WHERE l.run_id=r.run_id AND l.lease_until>clock_timestamp()))))
$$;
CREATE FUNCTION collection_receipt_context_valid(id uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM collection_run_worker k WHERE k.run_id=id
 AND k.worker_session_id::text=current_setting('eodiisseo.collection.worker',true))
$$;
CREATE FUNCTION collection_resolution_context_valid(id uuid) RETURNS boolean LANGUAGE sql VOLATILE AS $$
 SELECT collection_ops_context_valid() AND EXISTS(SELECT 1 FROM collection_run_resolution r
 JOIN collection_resolution_detail d USING(resolution_id)
 WHERE r.resolution_id::text=current_setting('eodiisseo.collection.resolution',true) AND r.run_id=id
 AND r.decision='RELEASE_CONFIRMED_STOPPED' AND r.reviewed_by::text=current_setting('eodiisseo.collection.ops_member',true)
 AND d.session_id::text=current_setting('eodiisseo.collection.ops_session',true) AND d.completion_reference IS NOT NULL)
$$;
CREATE FUNCTION collection_cutover_change_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'LEGACY' OR NEW.generation<>0 OR NEW.review_id IS NOT NULL THEN RAISE EXCEPTION 'Initial barrier must remain legacy'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP<>'UPDATE' OR NEW.singleton IS DISTINCT FROM OLD.singleton OR NEW.generation<>OLD.generation+1
 OR NEW.state NOT IN ('AUDIT_UNIFIED','FROZEN') OR NOT collection_ops_context_valid() THEN
  RAISE EXCEPTION 'Explicit reviewed cutover required'; END IF;
 IF current_database() !~ '^collection4b1_[a-f0-9]{32}$' OR inet_server_port()<>55433 THEN
  RAISE EXCEPTION '4B-1 cutover is disposable-audit only'; END IF;
 IF NEW.state='AUDIT_UNIFIED' THEN
  IF OLD.state='AUDIT_UNIFIED' OR EXISTS(SELECT 1 FROM collection_run WHERE status='RUNNING')
  OR EXISTS(SELECT 1 FROM collection_job_lock) OR EXISTS(SELECT 1 FROM collection_target_lease WHERE claim_id IS NOT NULL)
  OR EXISTS(SELECT 1 FROM collection_http_attempt WHERE state IN ('IN_FLIGHT','RECEIVED','UNKNOWN')) THEN
   RAISE EXCEPTION 'Drain and new review required'; END IF;
  IF NOT EXISTS(SELECT 1 FROM collection_transition_review m
  JOIN collection_transition_review d ON d.review_id=m.parent_review_id AND d.step='DRAIN_VERIFIED'
  JOIN collection_transition_review s ON s.review_id=d.parent_review_id AND s.step='STOP_VERIFIED'
  WHERE m.review_id=NEW.review_id AND m.step='MIGRATIONS_VERIFIED' AND m.synthetic AND d.synthetic AND s.synthetic
  AND least(m.valid_until,d.valid_until,s.valid_until)>clock_timestamp() AND (OLD.state<>'FROZEN' OR s.created_at>OLD.changed_at)) THEN RAISE EXCEPTION 'Audit review chain required'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER collection_cutover_change_guard BEFORE INSERT OR UPDATE OR DELETE ON collection_cutover_barrier FOR EACH ROW EXECUTE FUNCTION collection_cutover_change_guard();
CREATE FUNCTION collection_cutover_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE kind text:=TG_ARGV[0]; n jsonb; o jsonb; id uuid; source uuid; ctx uuid; ok boolean;
BEGIN
 IF (SELECT state FROM collection_cutover_barrier WHERE singleton)='LEGACY' THEN
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
 END IF;
 IF TG_OP<>'DELETE' THEN n:=to_jsonb(NEW); END IF; IF TG_OP<>'INSERT' THEN o:=to_jsonb(OLD); END IF;
 IF kind='RAWREV' AND TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Raw revision history is append-only'; END IF;
 IF kind='OWNED' THEN RAISE EXCEPTION 'Legacy owned collection writes are frozen'; END IF;
 ctx:=nullif(current_setting('eodiisseo.collection.run',true),'')::uuid;
 id:=coalesce((n->>'run_id')::uuid,(o->>'run_id')::uuid,ctx);
 IF kind IN ('RUN','LOCK','LEASE','EXECUTION','ATTEMPT') AND collection_resolution_context_valid(id) THEN
  IF kind='RUN' AND (TG_OP<>'UPDATE' OR n->>'status'<>'FAILED' OR (n-'status'-'finished_at'-'error_code') IS DISTINCT FROM (o-'status'-'finished_at'-'error_code')) THEN RAISE EXCEPTION 'Resolution cannot rewrite execution history'; END IF;
  IF kind='LOCK' AND TG_OP<>'DELETE' THEN RAISE EXCEPTION 'Resolution only releases an exact source lock'; END IF;
  IF kind='LEASE' AND (TG_OP<>'UPDATE' OR n->>'run_id' IS NOT NULL OR n->>'claim_id' IS NOT NULL OR n->>'lease_until' IS NOT NULL
    OR (n-'run_id'-'claim_id'-'lease_until') IS DISTINCT FROM (o-'run_id'-'claim_id'-'lease_until')) THEN RAISE EXCEPTION 'Resolution must retain target and fence generation'; END IF;
  IF kind='ATTEMPT' AND (TG_OP<>'UPDATE' OR n->>'state'<>'REJECTED' OR (n-'state'-'finished_at') IS DISTINCT FROM (o-'state'-'finished_at')) THEN RAISE EXCEPTION 'Resolution must preserve attempt provenance'; END IF;
  IF kind='EXECUTION' AND (TG_OP<>'UPDATE' OR n->>'state'<>'FAILED' OR (n-'state'-'reason'-'domain_phase') IS DISTINCT FROM (o-'state'-'reason'-'domain_phase')) THEN RAISE EXCEPTION 'Resolution only records failure'; END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
 END IF;
 IF kind='ARCHIVE' THEN
  SELECT a.run_id INTO id FROM collection_http_attempt a WHERE a.attempt_id=(coalesce(n,o)->>'attempt_id')::uuid;
  IF TG_OP='INSERT' AND n->>'classification'='QUARANTINE' AND collection_receipt_context_valid(id) THEN RETURN NEW; END IF;
  IF TG_OP='UPDATE' AND (n-'classification'-'reason') IS DISTINCT FROM (o-'classification'-'reason') THEN
   RAISE EXCEPTION 'Archived bytes and provenance are immutable'; END IF;
 ELSIF kind='ATTEMPT' THEN
  IF TG_OP='UPDATE' AND o->>'state' IN ('IN_FLIGHT','RECEIVED','UNKNOWN') AND n->>'state' IN ('UNKNOWN','RECEIVED')
  AND (n-'state'-'finished_at'-'http_status')=(o-'state'-'finished_at'-'http_status')
  AND collection_receipt_context_valid(id) THEN RETURN NEW; END IF;
 ELSIF kind='EXECUTION' THEN
  IF TG_OP='UPDATE' AND n->>'state'='UNKNOWN' AND n->>'domain_phase' IS NOT DISTINCT FROM o->>'domain_phase'
  AND (n-'state'-'reason')=(o-'state'-'reason') AND collection_receipt_context_valid(id) THEN RETURN NEW; END IF;
 END IF;
 IF NOT collection_worker_context_valid() THEN RAISE EXCEPTION 'Legacy or stale worker denied by collection barrier'; END IF;
 IF kind='LEASE' THEN
  IF NOT EXISTS(SELECT 1 FROM collection_worker_session w JOIN collection_source_assignment a ON a.source_id=ANY(w.sources)
   JOIN data_source s ON s.source_id=a.source_id AND s.collection_assignment_epoch=a.epoch
   WHERE w.worker_session_id::text=current_setting('eodiisseo.collection.worker',true) AND a.target_id=(coalesce(n,o)->>'target_id')::uuid)
   THEN RAISE EXCEPTION 'Target outside worker scope'; END IF;
 ELSIF kind='QUOTA' THEN
  IF TG_OP<>'UPDATE' OR (n-'reserved'-'next_request_at') IS DISTINCT FROM (o-'reserved'-'next_request_at') THEN
   RAISE EXCEPTION 'Quota policy changes require stopped review'; END IF;
 ELSIF kind='RUN' AND TG_OP='INSERT' THEN
  IF n->>'execution_owner'<>'COMMON_COLLECTION_V1' OR n->>'run_id' IS DISTINCT FROM current_setting('eodiisseo.collection.run',true)
   OR n->>'claim_id' IS DISTINCT FROM current_setting('eodiisseo.collection.claim',true)
   OR n->>'fence_token' IS DISTINCT FROM current_setting('eodiisseo.collection.fence',true)
   OR NOT EXISTS(SELECT 1 FROM collection_worker_session w WHERE w.worker_session_id::text=current_setting('eodiisseo.collection.worker',true)
    AND (n->>'source_id')::uuid=ANY(w.sources)) THEN RAISE EXCEPTION 'Owned claim context required'; END IF;
 ELSE
  IF TG_OP='DELETE' AND kind<>'LOCK' THEN RAISE EXCEPTION 'Collection history must be retained'; END IF;
  IF NOT collection_run_context_valid(id,kind IN ('RAW','RAWREV','FACT','ARCHIVE')) THEN RAISE EXCEPTION 'Run or fence context invalid'; END IF;
  IF kind='FACT' THEN
   IF (n->>'target_id')::uuid IS DISTINCT FROM (SELECT target_id FROM collection_run WHERE run_id=id) THEN RAISE EXCEPTION 'Fact target mismatch'; END IF;
   IF TG_TABLE_NAME='collection_domain_cursor' THEN
    IF (n->>'last_run_id')::uuid IS DISTINCT FROM id THEN RAISE EXCEPTION 'Cursor run mismatch'; END IF;
    IF TG_OP='UPDATE' AND ((n->>'through_date')::date<(o->>'through_date')::date
       OR (n-'through_date'-'last_run_id') IS DISTINCT FROM (o-'through_date'-'last_run_id')) THEN RAISE EXCEPTION 'Cursor identity and monotonic history must be preserved'; END IF;
   END IF;
  END IF;
  IF kind IN ('RAW','RAWREV') THEN
   IF kind='RAW' THEN source:=(coalesce(n,o)->>'source_id')::uuid;
   ELSE SELECT source_id INTO source FROM collection_raw_record WHERE record_id=(coalesce(n,o)->>'record_id')::uuid; END IF;
   IF NOT EXISTS(SELECT 1 FROM collection_run r JOIN collection_operation_approval p ON p.source_id=r.source_id AND p.epoch=r.assignment_epoch
    WHERE r.run_id=id AND p.canonical_source_id=source AND p.target_id=r.target_id AND p.contract_key=r.contract_key
    AND p.projection_allowed AND p.valid_until>clock_timestamp()
    AND NOT EXISTS(SELECT 1 FROM collection_approval_revocation v WHERE v.approval_id=p.approval_id)) THEN RAISE EXCEPTION 'Canonical source not approved'; END IF;
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE FUNCTION collection_bind_run_worker() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (SELECT state FROM collection_cutover_barrier WHERE singleton)='AUDIT_UNIFIED' THEN
 INSERT INTO collection_run_worker VALUES(NEW.run_id,current_setting('eodiisseo.collection.worker')::uuid,NEW.claim_id,NEW.fence_token);
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER collection_bind_run_worker AFTER INSERT ON collection_run FOR EACH ROW EXECUTE FUNCTION collection_bind_run_worker();
CREATE TRIGGER collection_barrier_run BEFORE INSERT OR UPDATE OR DELETE ON collection_run FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('RUN');
CREATE TRIGGER collection_barrier_lock BEFORE INSERT OR UPDATE OR DELETE ON collection_job_lock FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('LOCK');
CREATE TRIGGER collection_barrier_lease BEFORE INSERT OR UPDATE OR DELETE ON collection_target_lease FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('LEASE');
CREATE TRIGGER collection_barrier_raw BEFORE INSERT OR UPDATE OR DELETE ON collection_raw_record FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('RAW');
CREATE TRIGGER collection_barrier_raw_revision BEFORE INSERT OR UPDATE OR DELETE ON collection_raw_revision FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('RAWREV');
CREATE TRIGGER collection_barrier_execution BEFORE INSERT OR UPDATE OR DELETE ON collection_http_execution FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('EXECUTION');
CREATE TRIGGER collection_barrier_attempt BEFORE INSERT OR UPDATE OR DELETE ON collection_http_attempt FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('ATTEMPT');
CREATE TRIGGER collection_barrier_archive BEFORE INSERT OR UPDATE OR DELETE ON collection_http_archive FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('ARCHIVE');
CREATE TRIGGER collection_barrier_quota BEFORE INSERT OR UPDATE OR DELETE ON collection_quota_window FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('QUOTA');
CREATE TRIGGER collection_barrier_cursor BEFORE INSERT OR UPDATE OR DELETE ON collection_domain_cursor FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('FACT');
CREATE TRIGGER collection_barrier_coverage BEFORE INSERT OR UPDATE OR DELETE ON collection_domain_coverage FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('FACT');
CREATE TRIGGER collection_barrier_projection BEFORE INSERT OR UPDATE OR DELETE ON collection_projection_receipt FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('FACT');
CREATE TRIGGER collection_barrier_owned_cursor BEFORE INSERT OR UPDATE OR DELETE ON owned_collection_cursor FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('OWNED');
CREATE TRIGGER collection_barrier_owned_page BEFORE INSERT OR UPDATE OR DELETE ON owned_collection_page FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('OWNED');
CREATE TRIGGER collection_barrier_owned_item BEFORE INSERT OR UPDATE OR DELETE ON owned_collection_item FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('OWNED');
CREATE TRIGGER collection_barrier_owned_coverage BEFORE INSERT OR UPDATE OR DELETE ON owned_collection_coverage FOR EACH ROW EXECUTE FUNCTION collection_cutover_write_guard('OWNED');
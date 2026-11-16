-- Optional preparation. No keys, roles, grants, fleet approval or runtime activation is seeded.
CREATE TABLE collection_production.command_key (
 worker_id uuid PRIMARY KEY REFERENCES collection_production.worker(worker_id),
 public_key bytea NOT NULL CHECK(octet_length(public_key) BETWEEN 32 AND 128),
 peer_sha256 text NOT NULL CHECK(peer_sha256 ~ '^[a-f0-9]{64}$'),
 valid_until timestamptz NOT NULL, revoked_at timestamptz
);
CREATE TABLE collection_production.command_receipt (
 command_id uuid PRIMARY KEY,worker_id uuid NOT NULL REFERENCES collection_production.worker(worker_id),
 digest text NOT NULL CHECK(digest ~ '^[a-f0-9]{64}$'),run_id uuid NOT NULL REFERENCES public.collection_run(run_id),
 claim_id uuid NOT NULL,fence_token bigint NOT NULL CHECK(fence_token>0),generation bigint NOT NULL,
 expires_at timestamptz NOT NULL,state text NOT NULL CHECK(state IN ('PROCESSING','COMPLETED')),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),completed_at timestamptz,
 CHECK((state='COMPLETED')=(completed_at IS NOT NULL))
);
CREATE TABLE collection_production.command_failure (
 attempt_id uuid PRIMARY KEY DEFAULT gen_random_uuid(), command_id uuid NOT NULL,
 worker_id uuid NOT NULL REFERENCES collection_production.worker(worker_id),
 digest text NOT NULL CHECK(digest ~ '^[a-f0-9]{64}$'),
 outcome text NOT NULL CHECK(outcome IN ('ROLLED_BACK','OUTCOME_UNKNOWN')),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER production_command_failure_immutable BEFORE UPDATE OR DELETE ON collection_production.command_failure FOR EACH ROW EXECUTE FUNCTION public.collection_operations_append_only();
ALTER TABLE collection_production.command_failure ENABLE ALWAYS TRIGGER production_command_failure_immutable;
CREATE FUNCTION collection_production.register_command_key(m uuid,s uuid,w uuid,key_der bytea,peer text,until_at timestamptz) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(78161108);PERFORM collection_production.authorize_ops(m,s);
 IF NOT EXISTS(SELECT 1 FROM collection_production.worker WHERE worker_id=w AND revoked_at IS NULL AND valid_until>=until_at) OR until_at<=clock_timestamp() THEN RAISE EXCEPTION 'Current worker key scope required'; END IF;
 INSERT INTO collection_production.command_key VALUES(w,key_der,peer,until_at,NULL);
END $$;
CREATE FUNCTION collection_production.revoke_command_key(m uuid,s uuid,w uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(78161108);PERFORM collection_production.authorize_ops(m,s);
 UPDATE collection_production.command_key SET revoked_at=clock_timestamp() WHERE worker_id=w AND revoked_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Current key required'; END IF;
END $$;
CREATE FUNCTION collection_production.command_authority(wid uuid,src uuid,rid uuid,cid uuid,fence bigint,gen bigint)
RETURNS TABLE(key_der bytea,peer_sha256 text,until_at timestamptz,source_alias text,target_id uuid,contract_key text,quota_scope_id text,assignment_epoch bigint,execution_owner text,approval_reference text,quiesced boolean,audit_only boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE gateway_role=session_user) OR NOT collection_production.proofs_valid() THEN RAISE EXCEPTION 'Gateway command authority denied'; END IF;
 PERFORM k.worker_id FROM collection_production.command_key k WHERE k.worker_id=wid FOR SHARE;
 RETURN QUERY SELECT k.public_key,k.peer_sha256,least(k.valid_until,w.valid_until),a.source_alias::text,a.target_id,a.contract_key::text,a.quota_scope_id::text,a.epoch,a.execution_owner::text,a.approval_reference::text,a.legacy_workers_quiesced,i.audit_only
 FROM collection_production.command_key k JOIN collection_production.worker w USING(worker_id)
 JOIN collection_production.installation i ON true
 JOIN public.collection_run r ON r.run_id=rid
 JOIN public.data_source d ON d.source_id=r.source_id
 JOIN public.collection_source_assignment a ON a.source_id=d.source_id AND a.epoch=d.collection_assignment_epoch
 WHERE w.worker_id=wid AND w.generation=gen AND w.generation=(SELECT generation FROM collection_production.barrier)
 AND src=ANY(w.sources) AND r.source_id=src AND r.claim_id=cid AND r.fence_token=fence AND r.status='RUNNING'
 AND r.assignment_epoch=a.epoch AND a.approved AND a.legacy_workers_quiesced AND d.active
 AND k.revoked_at IS NULL AND w.revoked_at IS NULL AND least(k.valid_until,w.valid_until)>clock_timestamp()
 AND collection_production.ops_valid(w.member_id,w.session_id);
END $$;
CREATE FUNCTION collection_production.reserve_command(id uuid,hash text,request_until timestamptz) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
DECLARE c collection_production.context;r collection_production.command_receipt;g bigint;
BEGIN
 PERFORM collection_production.assert_context();
 SELECT * INTO c FROM collection_production.context WHERE transaction_id=pg_current_xact_id() AND backend_pid=pg_backend_pid();
 IF request_until IS NULL OR request_until<=clock_timestamp() THEN RAISE EXCEPTION 'Signed command deadline required'; END IF;
 IF c.phase<>'EXECUTION' OR c.closed THEN RAISE EXCEPTION 'Execution command context required'; END IF;
 SELECT generation INTO g FROM collection_production.worker WHERE worker_id=c.worker_id;
 SELECT * INTO r FROM collection_production.command_receipt WHERE command_id=id FOR UPDATE;
 IF FOUND THEN
  IF r.worker_id<>c.worker_id OR r.digest<>hash OR r.run_id<>c.run_id OR r.claim_id<>c.claim_id OR r.fence_token<>c.fence_token OR r.generation<>g OR r.expires_at<>request_until OR r.state<>'COMPLETED' THEN RAISE EXCEPTION 'Command replay conflict'; END IF;
  RETURN false;
 END IF;
 INSERT INTO collection_production.command_receipt(command_id,worker_id,digest,run_id,claim_id,fence_token,generation,expires_at,state) VALUES(id,c.worker_id,hash,c.run_id,c.claim_id,c.fence_token,g,request_until,'PROCESSING');RETURN true;
END $$;
CREATE FUNCTION collection_production.complete_command(id uuid,hash text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
DECLARE c collection_production.context;
BEGIN
 PERFORM collection_production.assert_context();SELECT * INTO c FROM collection_production.context WHERE transaction_id=pg_current_xact_id() AND backend_pid=pg_backend_pid();
 UPDATE collection_production.command_receipt SET state='COMPLETED',completed_at=clock_timestamp() WHERE command_id=id AND digest=hash AND worker_id=c.worker_id AND run_id=c.run_id AND claim_id=c.claim_id AND fence_token=c.fence_token AND state='PROCESSING';
 IF NOT FOUND AND NOT EXISTS(SELECT 1 FROM collection_production.command_receipt WHERE command_id=id AND digest=hash AND worker_id=c.worker_id AND run_id=c.run_id AND state='COMPLETED') THEN RAISE EXCEPTION 'Command completion conflict'; END IF;
END $$;
CREATE FUNCTION collection_production.command_commit_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 PERFORM collection_production.assert_context(true);
 IF NOT EXISTS(SELECT 1 FROM collection_production.command_receipt WHERE command_id=NEW.command_id AND state='COMPLETED' AND expires_at>clock_timestamp()) THEN RAISE EXCEPTION 'Incomplete command cannot commit'; END IF;
 IF NOT EXISTS(SELECT 1 FROM collection_production.command_key WHERE worker_id=NEW.worker_id AND revoked_at IS NULL AND valid_until>clock_timestamp()) THEN RAISE EXCEPTION 'Command key expired before COMMIT'; END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER production_command_commit AFTER INSERT ON collection_production.command_receipt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION collection_production.command_commit_guard();
ALTER TABLE collection_production.command_receipt ENABLE ALWAYS TRIGGER production_command_commit;
CREATE FUNCTION collection_production.record_command_failure(wid uuid,id uuid,hash text,outcome text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE gateway_role=session_user) THEN RAISE EXCEPTION 'Gateway failure audit denied'; END IF;
 INSERT INTO collection_production.command_failure(command_id,worker_id,digest,outcome) VALUES(id,wid,hash,CASE WHEN EXISTS(SELECT 1 FROM collection_production.command_receipt WHERE command_id=id AND state='COMPLETED') THEN 'OUTCOME_UNKNOWN' ELSE outcome END);
END $$;
-- Optional DB fleet fence: absent means legacy preparation, never readiness approval.
CREATE TABLE collection_production.fleet_fence (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),epoch bigint NOT NULL CHECK(epoch>0),
 artifact_id uuid NOT NULL REFERENCES public.collection_assurance_artifact(artifact_id),fingerprint text NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'),valid_until timestamptz NOT NULL,revoked boolean NOT NULL
);
CREATE TABLE collection_production.fleet_fence_history (
 event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),epoch bigint NOT NULL,artifact_id uuid NOT NULL REFERENCES public.collection_assurance_artifact(artifact_id),revoked boolean NOT NULL,recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER production_fleet_history_immutable BEFORE UPDATE OR DELETE ON collection_production.fleet_fence_history FOR EACH ROW EXECUTE FUNCTION public.collection_operations_append_only();
ALTER TABLE collection_production.fleet_fence_history ENABLE ALWAYS TRIGGER production_fleet_history_immutable;
CREATE TABLE collection_production.fleet_context (
 transaction_id xid8 NOT NULL,backend_pid integer NOT NULL,epoch bigint NOT NULL,fingerprint text NOT NULL,
 PRIMARY KEY(transaction_id,backend_pid),FOREIGN KEY(transaction_id,backend_pid) REFERENCES collection_production.context(transaction_id,backend_pid)
);
CREATE FUNCTION collection_production.publish_fleet_fence(m uuid,s uuid,expected bigint,artifact uuid,hash text,until_at timestamptz,revoked_state boolean) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
DECLARE previous bigint;
BEGIN
 PERFORM pg_advisory_xact_lock(78161108);PERFORM collection_production.authorize_ops(m,s);
 SELECT epoch INTO previous FROM collection_production.fleet_fence FOR UPDATE;
 IF coalesce(previous,0)<>expected OR (previous IS NULL AND EXISTS(SELECT 1 FROM collection_production.fleet_fence_history)) THEN RAISE EXCEPTION 'Fleet fence generation conflict'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.collection_assurance_artifact a JOIN collection_production.installation i ON true WHERE a.artifact_id=artifact AND a.kind='FLEET' AND a.payload_sha256=hash AND a.synthetic=i.audit_only AND a.valid_from<=clock_timestamp() AND a.valid_until>=until_at AND NOT EXISTS(SELECT 1 FROM public.collection_assurance_revocation r WHERE r.artifact_id=a.artifact_id)) OR until_at<=clock_timestamp() THEN RAISE EXCEPTION 'Current reviewed Fleet required'; END IF;
 INSERT INTO collection_production.fleet_fence VALUES(true,expected+1,artifact,hash,until_at,revoked_state)
 ON CONFLICT(singleton) DO UPDATE SET epoch=excluded.epoch,artifact_id=excluded.artifact_id,fingerprint=excluded.fingerprint,valid_until=excluded.valid_until,revoked=excluded.revoked;
 INSERT INTO collection_production.fleet_fence_history(epoch,artifact_id,revoked) VALUES(expected+1,artifact,revoked_state);
 IF previous IS NOT NULL OR revoked_state THEN
  UPDATE collection_production.barrier SET state='FROZEN',generation=generation+1,valid_until=NULL,changed_at=clock_timestamp();
 END IF;
END $$;
CREATE FUNCTION collection_production.bind_fleet_context() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
DECLARE f collection_production.fleet_fence;
BEGIN
 SELECT * INTO f FROM collection_production.fleet_fence FOR SHARE;
 IF NOT FOUND THEN IF EXISTS(SELECT 1 FROM collection_production.fleet_fence_history) OR EXISTS(SELECT 1 FROM collection_production.installation WHERE NOT audit_only) THEN RAISE EXCEPTION 'Fleet fence missing'; END IF;RETURN NEW;END IF;
 IF f.revoked OR f.valid_until<=clock_timestamp() OR NOT EXISTS(SELECT 1 FROM public.collection_assurance_artifact a WHERE a.artifact_id=f.artifact_id AND a.payload_sha256=f.fingerprint AND a.valid_until>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM public.collection_assurance_revocation r WHERE r.artifact_id=a.artifact_id)) THEN RAISE EXCEPTION 'Fleet authority withdrawn'; END IF;
 INSERT INTO collection_production.fleet_context VALUES(NEW.transaction_id,NEW.backend_pid,f.epoch,f.fingerprint);RETURN NEW;
END $$;
CREATE TRIGGER production_bind_fleet AFTER INSERT ON collection_production.context FOR EACH ROW EXECUTE FUNCTION collection_production.bind_fleet_context();
ALTER TABLE collection_production.context ENABLE ALWAYS TRIGGER production_bind_fleet;
CREATE FUNCTION collection_production.fleet_commit_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM collection_production.fleet_context WHERE transaction_id=NEW.transaction_id AND backend_pid=NEW.backend_pid) AND NOT EXISTS(
 SELECT 1 FROM collection_production.fleet_context c JOIN collection_production.fleet_fence f ON c.epoch=f.epoch AND c.fingerprint=f.fingerprint
 JOIN public.collection_assurance_artifact a ON a.artifact_id=f.artifact_id
 WHERE c.transaction_id=NEW.transaction_id AND c.backend_pid=NEW.backend_pid AND NOT f.revoked AND f.valid_until>clock_timestamp() AND a.valid_until>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM public.collection_assurance_revocation r WHERE r.artifact_id=a.artifact_id)) THEN RAISE EXCEPTION 'Fleet changed before COMMIT';END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER production_fleet_commit AFTER INSERT ON collection_production.context DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION collection_production.fleet_commit_guard();
ALTER TABLE collection_production.context ENABLE ALWAYS TRIGGER production_fleet_commit;
REVOKE ALL ON collection_production.command_key,collection_production.command_receipt,collection_production.command_failure,collection_production.fleet_fence,collection_production.fleet_fence_history,collection_production.fleet_context FROM PUBLIC;
REVOKE ALL ON FUNCTION collection_production.register_command_key(uuid,uuid,uuid,bytea,text,timestamptz),collection_production.revoke_command_key(uuid,uuid,uuid),collection_production.command_authority(uuid,uuid,uuid,uuid,bigint,bigint),collection_production.reserve_command(uuid,text,timestamptz),collection_production.complete_command(uuid,text),collection_production.record_command_failure(uuid,uuid,text,text),collection_production.command_commit_guard(),collection_production.publish_fleet_fence(uuid,uuid,bigint,uuid,text,timestamptz,boolean),collection_production.bind_fleet_context(),collection_production.fleet_commit_guard() FROM PUBLIC;

CREATE FUNCTION collection_production.command_receipt_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Command receipt is immutable'; END IF;
 IF OLD.state<>'PROCESSING' OR NEW.state<>'COMPLETED' OR (to_jsonb(OLD)-ARRAY['state','completed_at'])<>(to_jsonb(NEW)-ARRAY['state','completed_at']) THEN RAISE EXCEPTION 'Command receipt transition denied'; END IF;RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION collection_production.command_receipt_guard() FROM PUBLIC;
CREATE TRIGGER production_command_receipt_guard BEFORE UPDATE OR DELETE ON collection_production.command_receipt FOR EACH ROW EXECUTE FUNCTION collection_production.command_receipt_guard();
ALTER TABLE collection_production.command_receipt ENABLE ALWAYS TRIGGER production_command_receipt_guard;

CREATE UNIQUE INDEX uq_live_command_peer ON collection_production.command_key(peer_sha256) WHERE revoked_at IS NULL;
CREATE FUNCTION collection_production.command_peer(peer text) RETURNS TABLE(worker_id uuid,until_at timestamptz,audit_only boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,collection_production,public AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM collection_production.installation WHERE gateway_role=session_user) OR NOT collection_production.proofs_valid() THEN RAISE EXCEPTION 'Gateway peer lookup denied'; END IF;
 RETURN QUERY SELECT w.worker_id,least(w.valid_until,k.valid_until),i.audit_only FROM collection_production.command_key k JOIN collection_production.worker w USING(worker_id) JOIN collection_production.installation i ON true
 WHERE k.peer_sha256=peer AND k.revoked_at IS NULL AND w.revoked_at IS NULL AND least(k.valid_until,w.valid_until)>clock_timestamp() AND w.generation=(SELECT generation FROM collection_production.barrier) AND collection_production.ops_valid(w.member_id,w.session_id);
END $$;
REVOKE ALL ON FUNCTION collection_production.command_peer(text) FROM PUBLIC;

-- Additive ownership infrastructure only. No seeds, legacy backfill, scheduler or worker activation.
CREATE TABLE collection_contract (
    contract_key varchar(100) PRIMARY KEY,
    approved boolean NOT NULL DEFAULT false,
    CHECK (btrim(contract_key) <> '')
);
CREATE TABLE collection_target (
    target_id uuid PRIMARY KEY,
    target_key varchar(200) NOT NULL UNIQUE,
    contract_key varchar(100) NOT NULL REFERENCES collection_contract(contract_key),
    approved boolean NOT NULL DEFAULT false,
    UNIQUE(target_id,contract_key),
    CHECK (btrim(target_key) <> '')
);
CREATE TABLE collection_source_assignment (
    source_id uuid NOT NULL REFERENCES data_source(source_id),
    epoch bigint NOT NULL CHECK(epoch > 0),
    source_alias varchar(100) NOT NULL CHECK(btrim(source_alias) <> ''),
    target_id uuid NOT NULL,
    contract_key varchar(100) NOT NULL,
    quota_scope_id varchar(200) NOT NULL CHECK(btrim(quota_scope_id) <> ''),
    execution_owner varchar(100) NOT NULL CHECK(execution_owner='COMMON_COLLECTION_V1'),
    approval_reference text NOT NULL CHECK(btrim(approval_reference) <> ''),
    approved boolean NOT NULL DEFAULT false,
    legacy_workers_quiesced boolean NOT NULL DEFAULT false,
    PRIMARY KEY(source_id,epoch),
    FOREIGN KEY(target_id,contract_key) REFERENCES collection_target(target_id,contract_key),
    UNIQUE(source_id,epoch,target_id,contract_key,quota_scope_id,execution_owner),
    CHECK(NOT approved OR legacy_workers_quiesced)
);
ALTER TABLE data_source ADD COLUMN collection_assignment_epoch bigint;
ALTER TABLE data_source ADD CONSTRAINT fk_source_collection_assignment
    FOREIGN KEY(source_id,collection_assignment_epoch) REFERENCES collection_source_assignment(source_id,epoch);
ALTER TABLE collection_run
    ADD COLUMN execution_owner varchar(100),
    ADD COLUMN assignment_epoch bigint,
    ADD COLUMN contract_key varchar(100),
    ADD COLUMN target_id uuid,
    ADD COLUMN quota_scope_id varchar(200),
    ADD COLUMN claim_id uuid,
    ADD COLUMN fence_token bigint,
    ADD COLUMN ownership_phase varchar(30);
ALTER TABLE collection_run ADD CONSTRAINT ck_run_ownership_snapshot CHECK (
    (execution_owner IS NULL AND assignment_epoch IS NULL AND contract_key IS NULL AND target_id IS NULL
      AND quota_scope_id IS NULL AND claim_id IS NULL AND fence_token IS NULL AND ownership_phase IS NULL)
    OR (execution_owner IS NOT NULL AND execution_owner='COMMON_COLLECTION_V1' AND assignment_epoch IS NOT NULL AND assignment_epoch>0
      AND contract_key IS NOT NULL AND target_id IS NOT NULL AND quota_scope_id IS NOT NULL
      AND claim_id IS NOT NULL AND fence_token IS NOT NULL AND fence_token>0 AND ownership_phase IS NOT NULL AND ownership_phase='RESERVED' AND mode='LIVE')
);
ALTER TABLE collection_run ADD CONSTRAINT fk_run_collection_assignment
    FOREIGN KEY(source_id,assignment_epoch,target_id,contract_key,quota_scope_id,execution_owner)
    REFERENCES collection_source_assignment(source_id,epoch,target_id,contract_key,quota_scope_id,execution_owner);
CREATE UNIQUE INDEX uq_run_owned_claim ON collection_run(claim_id) WHERE claim_id IS NOT NULL;
CREATE UNIQUE INDEX uq_run_owned_target_active ON collection_run(target_id,mode)
    WHERE execution_owner IS NOT NULL AND status='RUNNING';
ALTER TABLE collection_run ADD CONSTRAINT uq_run_owned_lease
    UNIQUE(run_id,target_id,mode,claim_id,fence_token);
CREATE TABLE collection_target_lease (
    target_id uuid NOT NULL REFERENCES collection_target(target_id),
    mode varchar(10) NOT NULL CHECK(mode='LIVE'),
    fence_token bigint NOT NULL CHECK(fence_token>0),
    claim_id uuid UNIQUE,
    run_id uuid UNIQUE,
    lease_until timestamptz,
    PRIMARY KEY(target_id,mode),
    CHECK ((claim_id IS NULL AND run_id IS NULL AND lease_until IS NULL)
      OR (claim_id IS NOT NULL AND run_id IS NOT NULL AND lease_until IS NOT NULL)),
    FOREIGN KEY(run_id,target_id,mode,claim_id,fence_token)
      REFERENCES collection_run(run_id,target_id,mode,claim_id,fence_token)
);
CREATE TABLE collection_run_resolution (
    resolution_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES collection_run(run_id),
    reviewed_by uuid NOT NULL REFERENCES member(member_id),
    decision varchar(30) NOT NULL CHECK(decision IN ('KEEP_HELD','RELEASE_CONFIRMED_STOPPED')),
    evidence_reference text NOT NULL CHECK(btrim(evidence_reference) <> ''),
    legacy_workers_quiesced boolean NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK(decision<>'RELEASE_CONFIRMED_STOPPED' OR legacy_workers_quiesced)
);
-- Identity is append-only. Approval may be revoked, but changing execution identity requires a new epoch.
CREATE FUNCTION collection_assignment_identity_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NOT EXISTS(SELECT 1 FROM data_source WHERE source_id=NEW.source_id AND endpoint_key=NEW.source_alias) THEN
            RAISE EXCEPTION 'Assignment source alias does not match data_source';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Collection assignment history is immutable'; END IF;
    IF ROW(NEW.source_id,NEW.epoch,NEW.source_alias,NEW.target_id,NEW.contract_key,NEW.quota_scope_id,
           NEW.execution_owner,NEW.approval_reference,NEW.legacy_workers_quiesced)
       IS DISTINCT FROM
       ROW(OLD.source_id,OLD.epoch,OLD.source_alias,OLD.target_id,OLD.contract_key,OLD.quota_scope_id,
           OLD.execution_owner,OLD.approval_reference,OLD.legacy_workers_quiesced) THEN
        RAISE EXCEPTION 'Collection assignment identity is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER collection_assignment_identity_guard BEFORE INSERT OR UPDATE OR DELETE ON collection_source_assignment
    FOR EACH ROW EXECUTE FUNCTION collection_assignment_identity_immutable();
CREATE FUNCTION collection_run_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.execution_owner,NEW.assignment_epoch,NEW.contract_key,NEW.target_id,NEW.quota_scope_id,
           NEW.claim_id,NEW.fence_token,NEW.ownership_phase)
       IS DISTINCT FROM
       ROW(OLD.execution_owner,OLD.assignment_epoch,OLD.contract_key,OLD.target_id,OLD.quota_scope_id,
           OLD.claim_id,OLD.fence_token,OLD.ownership_phase) THEN
        RAISE EXCEPTION 'Run ownership snapshot is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER collection_run_snapshot_guard BEFORE UPDATE ON collection_run
    FOR EACH ROW EXECUTE FUNCTION collection_run_snapshot_immutable();
CREATE FUNCTION collection_fence_history_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Target fencing history must be retained'; END IF;
    IF NEW.target_id IS DISTINCT FROM OLD.target_id OR NEW.mode IS DISTINCT FROM OLD.mode
       OR NEW.fence_token < OLD.fence_token
       OR (NEW.fence_token > OLD.fence_token AND
          (NEW.fence_token<>OLD.fence_token+1 OR OLD.claim_id IS NOT NULL OR NEW.claim_id IS NOT NULL)) THEN
        RAISE EXCEPTION 'Invalid target fencing transition';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER collection_fence_history_guard BEFORE UPDATE OR DELETE ON collection_target_lease
    FOR EACH ROW EXECUTE FUNCTION collection_fence_history_guard();
CREATE FUNCTION collection_resolution_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Collection resolution history is append-only';
END $$;
CREATE TRIGGER collection_resolution_append_only BEFORE UPDATE OR DELETE ON collection_run_resolution
    FOR EACH ROW EXECUTE FUNCTION collection_resolution_append_only();
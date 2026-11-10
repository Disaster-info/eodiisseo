-- Optional 4A management schema. No scheduler activation, legacy mutation, backfill or cutover.
CREATE TABLE collection_transition_review (
 review_id uuid PRIMARY KEY,
 parent_review_id uuid REFERENCES collection_transition_review(review_id),
 step varchar(30) NOT NULL CHECK(step IN ('STOP_VERIFIED','DRAIN_VERIFIED','MIGRATIONS_VERIFIED','FROZEN')),
 reviewed_by uuid NOT NULL REFERENCES member(member_id),
 session_id uuid NOT NULL,
 evidence_reference varchar(160) NOT NULL CHECK(evidence_reference ~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$'),
 evidence_fingerprint char(64) NOT NULL CHECK(evidence_fingerprint ~ '^[a-f0-9]{64}$'),
 valid_until timestamptz NOT NULL,
 synthetic boolean NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(valid_until>created_at)
);
CREATE TABLE collection_component_approval (
 approval_id uuid PRIMARY KEY,
 kind varchar(30) NOT NULL CHECK(kind IN ('LEGACY_FLEET','HTTP_SENDER','RAW_POLICY','DOMAIN_POLICY')),
 version varchar(100) NOT NULL CHECK(btrim(version)<>''),
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'),
 evidence_reference varchar(160) NOT NULL CHECK(evidence_reference ~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$'),
 valid_until timestamptz NOT NULL,
 reviewed_by uuid NOT NULL REFERENCES member(member_id), session_id uuid NOT NULL,
 synthetic boolean NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), CHECK(valid_until>created_at)
);
CREATE TABLE collection_operation_approval (
 approval_id uuid PRIMARY KEY,
 source_id uuid NOT NULL, epoch bigint NOT NULL,
 target_id uuid NOT NULL, contract_key varchar(100) NOT NULL,
 quota_scope_id varchar(200) NOT NULL,
 canonical_source_id uuid NOT NULL REFERENCES data_source(source_id),
 validation_version varchar(100) NOT NULL CHECK(btrim(validation_version)<>''),
 evidence_reference varchar(160) NOT NULL CHECK(evidence_reference ~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$'),
 projection_allowed boolean NOT NULL, coverage_allowed boolean NOT NULL,
 valid_until timestamptz NOT NULL,
 reviewed_by uuid NOT NULL REFERENCES member(member_id), session_id uuid NOT NULL,
 synthetic boolean NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), CHECK(valid_until>created_at),
 FOREIGN KEY(source_id,epoch) REFERENCES collection_source_assignment(source_id,epoch),
 FOREIGN KEY(target_id,contract_key) REFERENCES collection_target(target_id,contract_key),
 CHECK(NOT coverage_allowed OR projection_allowed)
);
CREATE TABLE collection_approval_revocation (
 approval_id uuid PRIMARY KEY,
 reviewed_by uuid NOT NULL REFERENCES member(member_id), session_id uuid NOT NULL,
 evidence_reference varchar(160) NOT NULL CHECK(evidence_reference ~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE collection_resolution_detail (
 resolution_id uuid PRIMARY KEY REFERENCES collection_run_resolution(resolution_id),
 request_id uuid NOT NULL UNIQUE,
 expected_snapshot char(64) NOT NULL CHECK(expected_snapshot ~ '^[a-f0-9]{64}$'),
 stop_review_id uuid REFERENCES collection_transition_review(review_id),
 completion_reference varchar(160),
 session_id uuid NOT NULL, synthetic boolean NOT NULL,
 CHECK(completion_reference IS NULL OR completion_reference ~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$')
);
CREATE UNIQUE INDEX uq_collection_one_release ON collection_run_resolution(run_id)
 WHERE decision='RELEASE_CONFIRMED_STOPPED';
CREATE INDEX ix_collection_operation_review ON collection_operation_approval(source_id,epoch,created_at);
CREATE FUNCTION collection_operations_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Collection operational decisions are append-only'; END $$;
CREATE TRIGGER collection_transition_immutable BEFORE UPDATE OR DELETE ON collection_transition_review FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
CREATE TRIGGER collection_component_immutable BEFORE UPDATE OR DELETE ON collection_component_approval FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
CREATE TRIGGER collection_operation_immutable BEFORE UPDATE OR DELETE ON collection_operation_approval FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
CREATE TRIGGER collection_revocation_immutable BEFORE UPDATE OR DELETE ON collection_approval_revocation FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
CREATE TRIGGER collection_resolution_detail_immutable BEFORE UPDATE OR DELETE ON collection_resolution_detail FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
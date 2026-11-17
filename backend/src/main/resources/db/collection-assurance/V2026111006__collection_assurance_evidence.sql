-- Optional preparation-only assurance ledger. No roles/grants/production barrier/arming are installed.
CREATE TABLE collection_assurance_artifact (
 artifact_id uuid PRIMARY KEY,
 kind varchar(30) NOT NULL CHECK(kind IN ('FLEET','SENDER','DATABASE','ARCHIVE','DOMAIN_POLICY','API','PROJECTION','QUOTA','HTTP_COMPLETION')),
 scope_key varchar(120) NOT NULL CHECK(scope_key ~ '^[A-Z0-9_.:-]{1,120}$'),
 version varchar(100) NOT NULL CHECK(btrim(version)<>''),
 issuer varchar(100) NOT NULL CHECK(issuer ~ '^[A-Z0-9_.:-]{1,100}$'),
 reference varchar(160) NOT NULL CHECK(reference ~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$'),
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
 payload_sha256 char(64) NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
 review_signature bytea NOT NULL CHECK(octet_length(review_signature)=64),
 valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL CHECK(valid_until>valid_from),
 synthetic boolean NOT NULL,
 reviewed_by uuid NOT NULL REFERENCES member(member_id), session_id uuid NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(kind,scope_key,version)
);
CREATE TABLE collection_assurance_revocation (
 artifact_id uuid PRIMARY KEY REFERENCES collection_assurance_artifact(artifact_id),
 reviewed_by uuid NOT NULL REFERENCES member(member_id), session_id uuid NOT NULL,
 reference varchar(160) NOT NULL CHECK(reference ~ '^[A-Z0-9][A-Z0-9_.:-]{3,159}$'),
 revoked_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER collection_assurance_immutable BEFORE UPDATE OR DELETE ON collection_assurance_artifact FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
CREATE TRIGGER collection_assurance_revocation_immutable BEFORE UPDATE OR DELETE ON collection_assurance_revocation FOR EACH ROW EXECUTE FUNCTION collection_operations_append_only();
CREATE VIEW collection_assurance_metadata WITH (security_barrier=true) AS
 SELECT artifact_id,kind,scope_key,version,issuer,reference,payload_sha256,valid_from,valid_until,synthetic,reviewed_by,recorded_at
 FROM collection_assurance_artifact;
-- Never expose raw bytes, credential URLs or signed envelopes through worker/OPS metadata views.
CREATE VIEW collection_archive_metadata WITH (security_barrier=true) AS
 SELECT a.attempt_id,t.run_id,t.state,a.body_sha256,a.sensitive_omitted,a.classification,a.reason,a.expires_at,a.access_approval_reference
 FROM collection_http_archive a JOIN collection_http_attempt t USING(attempt_id);
REVOKE ALL ON collection_assurance_artifact,collection_assurance_revocation,collection_assurance_metadata,collection_archive_metadata FROM PUBLIC;
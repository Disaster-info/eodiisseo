-- Additive runtime state. Existing canonical migrations and data remain unchanged.
CREATE TABLE disaster_source_priority (
 source_id uuid PRIMARY KEY REFERENCES data_source(source_id), priority integer NOT NULL CHECK(priority>=0)
);
CREATE TABLE disaster_runtime_outbox (
 event_id uuid PRIMARY KEY, disaster_id uuid NOT NULL REFERENCES disaster(disaster_id),
 source_disaster_id uuid REFERENCES source_disaster(source_disaster_id),
 revision_id uuid REFERENCES source_disaster_revision(revision_id),
 event_kind varchar(30) NOT NULL CHECK(event_kind IN ('NEW','SEVERITY_INCREASE','REVISION')),
 previous_severity_id uuid REFERENCES severity_level(severity_id),
 current_severity_id uuid REFERENCES severity_level(severity_id),
 event_key varchar(100) NOT NULL UNIQUE, occurred_at timestamptz NOT NULL, created_at timestamptz NOT NULL,
 suppression_reasons jsonb NOT NULL DEFAULT '{}'::jsonb,
 available_at timestamptz NOT NULL, state varchar(12) NOT NULL DEFAULT 'PENDING'
 CHECK(state IN ('PENDING','WORKING','DONE','HELD')),
 claim_id uuid, lease_until timestamptz, attempts integer NOT NULL DEFAULT 0,
 last_error_code varchar(60), completed_at timestamptz
);
CREATE INDEX ix_disaster_outbox_due ON disaster_runtime_outbox(state,available_at);
CREATE TABLE summary_runtime_task (
 revision_id uuid PRIMARY KEY REFERENCES source_disaster_revision(revision_id),
 available_at timestamptz NOT NULL, state varchar(12) NOT NULL DEFAULT 'PENDING'
 CHECK(state IN ('PENDING','WORKING','DONE','HELD')),
 claim_id uuid, lease_until timestamptz, attempts integer NOT NULL DEFAULT 0,
 last_error_code varchar(60), completed_at timestamptz
);
ALTER TABLE notification_delivery ADD COLUMN retry_not_before timestamptz;
ALTER TABLE notification_delivery ADD COLUMN submission_authorized_at timestamptz;
CREATE TABLE ai_resource_review (
 resource_id uuid PRIMARY KEY REFERENCES official_resource(resource_id) ON DELETE CASCADE,
 revision_fingerprint char(64) NOT NULL, source_organization varchar(100) NOT NULL,
 source_url text NOT NULL, reviewed_at timestamptz NOT NULL, valid_until timestamptz NOT NULL,
 approved_public_ai_evidence boolean NOT NULL DEFAULT false, revoked_at timestamptz,
 evidence_reference varchar(500) NOT NULL, injection_reviewed boolean NOT NULL DEFAULT false,
 CHECK(valid_until>reviewed_at), CHECK(length(trim(evidence_reference))>0)
);
-- No approvals, consents, fake disasters or policy grants are seeded.

CREATE TABLE ai_external_consent (member_id uuid PRIMARY KEY REFERENCES member(member_id) ON DELETE CASCADE, granted boolean NOT NULL DEFAULT false, changed_at timestamptz NOT NULL);

-- The canonical schema omitted the unique revision constraint already required by SummaryJpaRepository.
-- Existing duplicates require explicit review; never discard or pick a summary in migration.
CREATE UNIQUE INDEX uq_ai_summary_runtime_revision ON ai_summary(source_revision_id);

CREATE UNIQUE INDEX uq_runtime_revision_source ON source_disaster_revision(revision_id,source_disaster_id);
ALTER TABLE disaster_runtime_outbox ADD CONSTRAINT fk_runtime_source_disaster
 FOREIGN KEY(source_disaster_id,disaster_id) REFERENCES source_disaster(source_disaster_id,disaster_id);
ALTER TABLE disaster_runtime_outbox ADD CONSTRAINT fk_runtime_revision_source
 FOREIGN KEY(revision_id,source_disaster_id) REFERENCES source_disaster_revision(revision_id,source_disaster_id);
ALTER TABLE disaster_runtime_outbox ADD CONSTRAINT ck_runtime_revision_context
 CHECK((source_disaster_id IS NULL)=(revision_id IS NULL) AND (event_kind<>'REVISION' OR revision_id IS NOT NULL));

CREATE FUNCTION runtime_immutable_official_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'Official revision snapshots are immutable'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_runtime_immutable_revision BEFORE UPDATE ON source_disaster_revision
 FOR EACH ROW EXECUTE FUNCTION runtime_immutable_official_revision();

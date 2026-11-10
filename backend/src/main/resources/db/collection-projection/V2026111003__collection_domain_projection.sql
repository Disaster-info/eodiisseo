-- Optional stage-3 schema. No seeds, reinterpretation/backfill, scheduler or operational activation.
ALTER TABLE collection_http_execution ADD COLUMN projection_required boolean, ADD COLUMN domain_phase varchar(30);
ALTER TABLE collection_http_execution ADD CONSTRAINT ck_domain_phase CHECK (
 (projection_required IS NULL AND domain_phase IS NULL) OR
 (projection_required IS NOT NULL AND projection_required AND domain_phase IS NOT NULL AND
 domain_phase IN ('HTTP_PENDING','DOMAIN_APPLIED','DOMAIN_REVIEW','DOMAIN_FAILED'))
);
CREATE TABLE collection_projection_receipt (
 receipt_id uuid PRIMARY KEY,
 run_id uuid NOT NULL REFERENCES collection_http_execution(run_id),
 attempt_id uuid NOT NULL REFERENCES collection_http_archive(attempt_id),
 target_id uuid NOT NULL REFERENCES collection_target(target_id),
 canonical_source_id uuid REFERENCES data_source(source_id),
 raw_record_id uuid,
 raw_revision_no integer,
 raw_key varchar(255) NOT NULL,
 raw_hash char(64),
 official_hash char(64),
 contract_key varchar(100) NOT NULL REFERENCES collection_contract(contract_key),
 normalizer_version varchar(100) NOT NULL,
 validation_version varchar(200) NOT NULL,
 validation_reference text NOT NULL,
 synthetic boolean NOT NULL,
 result varchar(80) NOT NULL,
 source_disaster_id uuid,
 disaster_id uuid,
 source_revision_id uuid REFERENCES source_disaster_revision(revision_id),
 mapping_version varchar(200),
 observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(run_id,attempt_id,raw_key),
 FOREIGN KEY(raw_record_id,raw_revision_no) REFERENCES collection_raw_revision(record_id,revision_no),
 FOREIGN KEY(source_disaster_id,disaster_id) REFERENCES source_disaster(source_disaster_id,disaster_id),
 CHECK((raw_record_id IS NULL AND raw_revision_no IS NULL AND raw_hash IS NULL) OR
       (raw_record_id IS NOT NULL AND raw_revision_no IS NOT NULL AND raw_hash IS NOT NULL AND raw_hash ~ '^[a-f0-9]{64}$')),
 CHECK((source_disaster_id IS NULL AND disaster_id IS NULL AND source_revision_id IS NULL) OR
       (source_disaster_id IS NOT NULL AND disaster_id IS NOT NULL AND source_revision_id IS NOT NULL AND official_hash IS NOT NULL)),
 CHECK(official_hash IS NULL OR official_hash ~ '^[a-f0-9]{64}$'),
 CHECK(result ~ '^[A-Z0-9_]{1,80}$')
);
CREATE TABLE collection_domain_coverage (
 run_id uuid PRIMARY KEY REFERENCES collection_http_execution(run_id),
 target_id uuid NOT NULL,
 contract_key varchar(100) NOT NULL,
 mode varchar(10) NOT NULL CHECK(mode='LIVE'),
 from_date date NOT NULL,
 to_date date NOT NULL,
 initial_date date NOT NULL,
 normalizer_version varchar(100) NOT NULL,
 validation_version varchar(200) NOT NULL,
 validation_reference text NOT NULL,
 synthetic boolean NOT NULL,
 verified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(target_id,contract_key) REFERENCES collection_target(target_id,contract_key),
 CHECK(to_date>=from_date AND to_date<=from_date+2)
);
CREATE TABLE collection_domain_cursor (
 target_id uuid NOT NULL REFERENCES collection_target(target_id),
 mode varchar(10) NOT NULL CHECK(mode='LIVE'),
 normalizer_version varchar(100) NOT NULL,
 validation_version varchar(200) NOT NULL,
 through_date date NOT NULL,
 initial_date date NOT NULL,
 last_run_id uuid NOT NULL REFERENCES collection_domain_coverage(run_id),
 PRIMARY KEY(target_id,mode,normalizer_version,validation_version),
 CHECK(through_date>=initial_date)
);
CREATE FUNCTION collection_projection_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Projection receipts are immutable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM collection_http_attempt a JOIN collection_run r USING(run_id)
   WHERE a.attempt_id=NEW.attempt_id AND a.run_id=NEW.run_id AND r.contract_key=NEW.contract_key AND r.target_id=NEW.target_id) THEN
   RAISE EXCEPTION 'Receipt does not match HTTP source';
 END IF;
 IF NEW.source_revision_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM source_disaster_revision
    WHERE revision_id=NEW.source_revision_id AND source_disaster_id=NEW.source_disaster_id AND content_hash=NEW.official_hash) THEN
   RAISE EXCEPTION 'Receipt source revision/hash mismatch';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER collection_projection_receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON collection_projection_receipt
 FOR EACH ROW EXECUTE FUNCTION collection_projection_receipt_guard();
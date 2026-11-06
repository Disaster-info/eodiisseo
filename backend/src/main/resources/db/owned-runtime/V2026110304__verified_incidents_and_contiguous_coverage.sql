-- New opt-in version only; never rewrite 0301/0302/0303 or canonical migrations.
CREATE TABLE owned_incident_head (
 source_id uuid NOT NULL REFERENCES data_source(source_id),
 incident_key varchar(180) NOT NULL,
 source_disaster_id uuid NOT NULL UNIQUE REFERENCES source_disaster(source_disaster_id) ON DELETE CASCADE,
 official_sequence bigint NOT NULL CHECK(official_sequence>0),
 issued_at timestamptz NOT NULL, announcement_key varchar(255) NOT NULL,
 content_sha256 char(64) NOT NULL, evidence_url text NOT NULL, contract_version varchar(200) NOT NULL,
 PRIMARY KEY(source_id,incident_key)
);
CREATE TABLE owned_collection_coverage (
 run_id uuid PRIMARY KEY REFERENCES collection_run(run_id) ON DELETE CASCADE,
 source_id uuid NOT NULL REFERENCES data_source(source_id),
 from_day date NOT NULL,to_day date NOT NULL,
 CHECK(to_day>=from_day AND to_day<=from_day+2)
);
CREATE INDEX owned_collection_coverage_source_window ON owned_collection_coverage(source_id,from_day,to_day);
ALTER TABLE owned_collection_cursor ADD COLUMN coverage_start date;
-- Existing last-success watermark is preserved; historical intervals are not fabricated.
UPDATE owned_collection_cursor SET coverage_start=completed_through WHERE completed_through IS NOT NULL;

-- Opt-in owned runtime only. No default collector activation or provider registrations.
-- Canonical data_source/collection_run/job_lock tables are reused without altering their contracts.
CREATE TABLE owned_collection_cursor (
 source_id uuid PRIMARY KEY REFERENCES data_source(source_id), completed_through date,
 next_attempt_at timestamptz NOT NULL DEFAULT '-infinity', last_success_at timestamptz, last_failure_code varchar(80),
 budget_day date, requests_today integer NOT NULL DEFAULT 0 CHECK(requests_today>=0)
);
CREATE TABLE owned_collection_page (
 page_id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES collection_run(run_id) ON DELETE CASCADE, page_no integer NOT NULL CHECK(page_no>0),
 raw_body bytea NOT NULL, body_sha256 char(64) NOT NULL, fetched_at timestamptz NOT NULL,
 UNIQUE(run_id,page_no)
);
CREATE TABLE owned_collection_item (
 item_id uuid PRIMARY KEY, source_id uuid NOT NULL REFERENCES data_source(source_id),
 provider_record_key varchar(255) NOT NULL, content_sha256 char(64) NOT NULL,
 canonical_json text NOT NULL, first_seen_at timestamptz NOT NULL, last_seen_at timestamptz NOT NULL,
 classification varchar(80) NOT NULL, source_disaster_id uuid REFERENCES source_disaster(source_disaster_id) ON DELETE SET NULL,
 UNIQUE(source_id,provider_record_key,content_sha256)
);
CREATE INDEX owned_collection_item_seen ON owned_collection_item(source_id,last_seen_at);
CREATE TABLE owned_resource_import (
 resource_id uuid PRIMARY KEY REFERENCES official_resource(resource_id) ON DELETE CASCADE,
 source_url text NOT NULL, content_sha256 char(64) NOT NULL, raw_body bytea NOT NULL,
 media_type varchar(100) NOT NULL, fetched_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 superseded_at timestamptz, UNIQUE(source_url,content_sha256)
);
CREATE INDEX owned_resource_import_url ON owned_resource_import(source_url,fetched_at DESC);

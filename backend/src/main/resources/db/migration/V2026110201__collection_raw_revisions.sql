-- Provider responses stay separate from normalized disaster facts.
-- Message creation/recipient/urgency must not become occurrence/impact/severity.
CREATE TABLE collection_raw_record (
 record_id uuid PRIMARY KEY,
 source_id uuid NOT NULL REFERENCES data_source(source_id),
 source_record_key varchar(255) NOT NULL,
 payload jsonb NOT NULL,
 content_hash char(64) NOT NULL,
 first_collected_at timestamptz NOT NULL,
 collected_at timestamptz NOT NULL,
 UNIQUE(source_id,source_record_key)
);
CREATE TABLE collection_raw_revision (
 record_id uuid NOT NULL REFERENCES collection_raw_record(record_id) ON DELETE CASCADE,
 revision_no integer NOT NULL CHECK(revision_no>0),
 payload jsonb NOT NULL,
 content_hash char(64) NOT NULL,
 collected_at timestamptz NOT NULL,
 PRIMARY KEY(record_id,revision_no)
);
CREATE INDEX ix_collection_raw_source_time ON collection_raw_record(source_id,collected_at);
INSERT INTO data_source(source_id,name,source_kind,endpoint_key,collection_interval_minutes,active) VALUES
 ('20000000-0000-0000-0000-000000000003','기상청 지진정보','DISASTER','KMA_EARTHQUAKE',5,true),
 ('20000000-0000-0000-0000-000000000004','기상청 기상특보','DISASTER','KMA_WARNING',5,true),
 ('20000000-0000-0000-0000-000000000005','행정안전부 긴급재난문자','DISASTER','SAFETYDATA_DISASTER_MESSAGE',5,true)
 ON CONFLICT(endpoint_key) DO NOTHING;
UPDATE data_source SET collection_interval_minutes=1440 WHERE endpoint_key='SAFETYDATA_EARTHQUAKE_SHELTER' AND collection_interval_minutes IS NULL;

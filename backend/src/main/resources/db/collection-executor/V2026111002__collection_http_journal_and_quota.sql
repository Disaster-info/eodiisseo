-- Optional stage-2 schema. NOT in the default Flyway location. No seeds or operational activation.
CREATE TABLE collection_quota_window (
    quota_scope_id varchar(200) NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    request_limit bigint NOT NULL CHECK(request_limit>0),
    reserved bigint NOT NULL DEFAULT 0 CHECK(reserved>=0 AND reserved<=request_limit),
    min_interval_ms integer NOT NULL CHECK(min_interval_ms>=0),
    next_request_at timestamptz NOT NULL,
    approval_reference text NOT NULL CHECK(btrim(approval_reference)<>''),
    PRIMARY KEY(quota_scope_id,starts_at),
    CHECK(ends_at>starts_at)
);
CREATE TABLE collection_http_execution (
    run_id uuid PRIMARY KEY,
    target_id uuid NOT NULL,
    mode varchar(10) NOT NULL CHECK(mode='LIVE'),
    claim_id uuid NOT NULL UNIQUE,
    fence_token bigint NOT NULL CHECK(fence_token>0),
    from_date date NOT NULL,
    to_date date NOT NULL,
    page_rows integer NOT NULL CHECK(page_rows BETWEEN 1 AND 100),
    timeout_ms bigint NOT NULL CHECK(timeout_ms BETWEEN 1 AND 15000),
    range_verified boolean NOT NULL,
    snapshot_verified boolean NOT NULL,
    empty_verified boolean NOT NULL,
    incremental_verified boolean NOT NULL,
    completeness_reference text NOT NULL CHECK(btrim(completeness_reference)<>''),
    state varchar(20) NOT NULL CHECK(state IN ('COLLECTING','COMPLETE','INCOMPLETE','FAILED','UNKNOWN','QUOTA_BLOCKED')),
    reason varchar(80),
    FOREIGN KEY(run_id,target_id,mode,claim_id,fence_token)
        REFERENCES collection_run(run_id,target_id,mode,claim_id,fence_token),
    CHECK(to_date>=from_date AND to_date<=from_date+2),
    CHECK(state<>'COMPLETE' OR (range_verified AND snapshot_verified AND incremental_verified))
);
CREATE TABLE collection_http_attempt (
    attempt_id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES collection_http_execution(run_id),
    page_no integer NOT NULL CHECK(page_no BETWEEN 1 AND 100),
    attempt_no integer NOT NULL CHECK(attempt_no BETWEEN 1 AND 3),
    quota_scope_id varchar(200) NOT NULL,
    quota_starts_at timestamptz NOT NULL,
    state varchar(20) NOT NULL CHECK(state IN ('IN_FLIGHT','RECEIVED','VALIDATED','RETRYABLE','REJECTED','UNKNOWN')),
    http_status integer CHECK(http_status BETWEEN 100 AND 599),
    declared_total integer CHECK(declared_total BETWEEN 0 AND 10000),
    item_count integer CHECK(item_count BETWEEN 0 AND 100),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    UNIQUE(run_id,page_no,attempt_no),
    CHECK(state<>'VALIDATED' OR (http_status IS NOT NULL AND http_status=200 AND declared_total IS NOT NULL AND item_count IS NOT NULL)),
    FOREIGN KEY(quota_scope_id,quota_starts_at) REFERENCES collection_quota_window(quota_scope_id,starts_at)
);
CREATE TABLE collection_http_archive (
    attempt_id uuid PRIMARY KEY REFERENCES collection_http_attempt(attempt_id),
    body_sha256 char(64) NOT NULL CHECK(body_sha256 ~ '^[a-f0-9]{64}$'),
    raw_body bytea CHECK(octet_length(raw_body)<=2097152),
    sensitive_omitted boolean NOT NULL,
    classification varchar(20) NOT NULL CHECK(classification IN ('QUARANTINE','NORMAL')),
    reason varchar(80) NOT NULL,
    expires_at timestamptz NOT NULL,
    access_approval_reference text NOT NULL CHECK(btrim(access_approval_reference)<>''),
    CHECK((sensitive_omitted AND raw_body IS NULL AND classification='QUARANTINE')
        OR (NOT sensitive_omitted AND raw_body IS NOT NULL))
);
CREATE INDEX ix_http_unresolved ON collection_http_attempt(run_id) WHERE state IN ('IN_FLIGHT','RECEIVED','UNKNOWN');
-- Make the original ownership protocol fail closed even if an older Java object tries to release a request.
CREATE FUNCTION collection_http_release_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='RUNNING' AND NEW.status<>'RUNNING' AND EXISTS
      (SELECT 1 FROM collection_http_attempt WHERE run_id=OLD.run_id AND state IN ('IN_FLIGHT','RECEIVED','UNKNOWN')) THEN
        RAISE EXCEPTION 'Unresolved external request requires explicit operator resolution';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER collection_http_release_guard BEFORE UPDATE ON collection_run
    FOR EACH ROW EXECUTE FUNCTION collection_http_release_guard();
CREATE UNIQUE INDEX uq_http_validated_page ON collection_http_attempt(run_id,page_no) WHERE state='VALIDATED';
CREATE FUNCTION collection_quota_no_refund() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.reserved<OLD.reserved THEN RAISE EXCEPTION 'Reserved calls cannot be automatically refunded'; END IF;
    IF ROW(NEW.quota_scope_id,NEW.starts_at,NEW.ends_at,NEW.min_interval_ms,NEW.approval_reference)
       IS DISTINCT FROM ROW(OLD.quota_scope_id,OLD.starts_at,OLD.ends_at,OLD.min_interval_ms,OLD.approval_reference) THEN
        RAISE EXCEPTION 'Quota contract changes require a new explicit window';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER collection_quota_no_refund BEFORE UPDATE ON collection_quota_window
    FOR EACH ROW EXECUTE FUNCTION collection_quota_no_refund();
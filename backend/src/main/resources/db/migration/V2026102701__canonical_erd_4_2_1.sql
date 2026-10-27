-- Canonical ERD 4.2.1 schema for a NEW database. Flyway owns the transaction.
-- 기준: 4.2.1 DB_ERD 설계서.docx. UUID/일시는 기본적으로 애플리케이션에서 제공한다.
-- 실행: psql -X -v ON_ERROR_STOP=1 -d <database> -f "4.2.1 DDL.sql"
-- PostGIS 패키지가 설치되어 있어야 하며 extension 생성 권한이 필요하다.
SET LOCAL TIME ZONE 'UTC';
CREATE EXTENSION IF NOT EXISTS postgis;

-- 내부 회원
CREATE TABLE member (
    member_id uuid NOT NULL,
    display_name varchar(50) NOT NULL,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    withdrawn_at timestamptz,
    PRIMARY KEY (member_id),
    CHECK ((status = 'ACTIVE' AND withdrawn_at IS NULL) OR (status = 'WITHDRAWAL_PENDING' AND withdrawn_at IS NOT NULL)),
    CHECK (status IN ('ACTIVE', 'WITHDRAWAL_PENDING'))
);
COMMENT ON TABLE member IS '내부 회원';

-- 소셜 인증 연결
CREATE TABLE social_account (
    social_account_id uuid NOT NULL,
    member_id uuid NOT NULL,
    provider varchar(20) NOT NULL,
    provider_user_id varchar(255) NOT NULL,
    linked_at timestamptz NOT NULL,
    PRIMARY KEY (social_account_id),
    UNIQUE (provider, provider_user_id)
);
COMMENT ON TABLE social_account IS '소셜 인증 연결';

-- 로그인 세션
CREATE TABLE auth_session (
    session_id uuid NOT NULL,
    member_id uuid NOT NULL,
    refresh_token_hash char(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    PRIMARY KEY (session_id),
    CHECK (expires_at > created_at AND expires_at = created_at + interval '336 hours')
);
COMMENT ON TABLE auth_session IS '로그인 세션';

-- 이미 사용한 갱신 토큰
CREATE TABLE auth_refresh_history (
    token_hash char(64) NOT NULL,
    session_id uuid NOT NULL,
    used_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (token_hash)
);
COMMENT ON TABLE auth_refresh_history IS '이미 사용한 갱신 토큰';

-- 전역 서비스 권한
CREATE TABLE member_role (
    member_id uuid NOT NULL,
    role varchar(10) NOT NULL,
    granted_at timestamptz NOT NULL,
    PRIMARY KEY (member_id, role),
    CHECK (role IN ('USER', 'OPS'))
);
COMMENT ON TABLE member_role IS '전역 서비스 권한';

-- 행정구역 기준
CREATE TABLE admin_area (
    area_code varchar(20) NOT NULL,
    name varchar(100) NOT NULL,
    level varchar(20) NOT NULL,
    parent_area_code varchar(20),
    center_point geography(Point,4326),
    active boolean NOT NULL,
    boundary geometry(MultiPolygon,4326),
    boundary_source_url text,
    boundary_source_updated_at timestamptz,
    boundary_collected_at timestamptz,
    boundary_source_provider varchar(30),
    boundary_source_area_code varchar(20),
    boundary_base_year integer,
    PRIMARY KEY (area_code),
    CHECK (boundary IS NULL OR (ST_SRID(boundary) = 4326 AND NOT ST_IsEmpty(boundary) AND ST_IsValid(boundary))),
    CHECK (boundary_base_year IS NULL OR boundary_base_year >= 1900),
    CHECK (center_point IS NULL OR (NOT ST_IsEmpty(center_point::geometry) AND ST_SRID(center_point::geometry) = 4326 AND ST_X(center_point::geometry) BETWEEN -180 AND 180 AND ST_Y(center_point::geometry) BETWEEN -90 AND 90))
);
COMMENT ON TABLE admin_area IS '행정구역 기준';

-- 관심지역
CREATE TABLE interest_area (
    interest_area_id uuid NOT NULL,
    member_id uuid NOT NULL,
    area_code varchar(20) NOT NULL,
    display_name varchar(50) NOT NULL,
    notify_enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (interest_area_id),
    UNIQUE (member_id, area_code)
);
COMMENT ON TABLE interest_area IS '관심지역';

-- 위치 이용 동의
CREATE TABLE member_location_consent (
    member_id uuid NOT NULL,
    consented boolean NOT NULL DEFAULT false,
    changed_at timestamptz NOT NULL,
    PRIMARY KEY (member_id)
);
COMMENT ON TABLE member_location_consent IS '위치 이용 동의';

-- 회원 최신 위치
CREATE TABLE member_location (
    member_id uuid NOT NULL,
    location_mode varchar(12) NOT NULL,
    point geography(Point,4326) NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (member_id),
    CHECK (location_mode IN ('GPS')),
    CHECK (point IS NULL OR (NOT ST_IsEmpty(point::geometry) AND ST_SRID(point::geometry) = 4326 AND ST_X(point::geometry) BETWEEN -180 AND 180 AND ST_Y(point::geometry) BETWEEN -90 AND 90))
);
COMMENT ON TABLE member_location IS '회원 최신 위치';

-- 그룹
CREATE TABLE safety_group (
    group_id uuid NOT NULL,
    name varchar(80) NOT NULL,
    category varchar(30) NOT NULL DEFAULT 'OTHER',
    created_by uuid,
    invite_code varchar(32) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (group_id),
    CHECK (category IN ('FAMILY', 'FRIEND', 'OTHER'))
);
COMMENT ON TABLE safety_group IS '그룹';

-- 그룹 구성원
CREATE TABLE group_member (
    group_id uuid NOT NULL,
    member_id uuid NOT NULL,
    role varchar(10) NOT NULL,
    location_sharing_enabled boolean NOT NULL DEFAULT false,
    joined_at timestamptz NOT NULL,
    PRIMARY KEY (group_id, member_id),
    CHECK (role IN ('ADMIN', 'MEMBER'))
);
COMMENT ON TABLE group_member IS '그룹 구성원';

-- 재난 유형
CREATE TABLE disaster_type (
    disaster_type_id uuid NOT NULL,
    code varchar(30) NOT NULL UNIQUE,
    name varchar(50) NOT NULL,
    active boolean NOT NULL,
    PRIMARY KEY (disaster_type_id)
);
COMMENT ON TABLE disaster_type IS '재난 유형';

-- 심각도 기준
CREATE TABLE severity_level (
    severity_id uuid NOT NULL,
    code varchar(30) NOT NULL UNIQUE,
    label varchar(50) NOT NULL,
    rank smallint NOT NULL UNIQUE,
    PRIMARY KEY (severity_id)
);
COMMENT ON TABLE severity_level IS '심각도 기준';

-- 외부 수집 대상
CREATE TABLE data_source (
    source_id uuid NOT NULL,
    name varchar(100) NOT NULL,
    source_kind varchar(20) NOT NULL,
    endpoint_key varchar(100) NOT NULL UNIQUE,
    collection_interval_minutes smallint,
    active boolean NOT NULL,
    PRIMARY KEY (source_id),
    CHECK (collection_interval_minutes IS NULL OR collection_interval_minutes > 0),
    CHECK (source_kind IN ('DISASTER', 'WEATHER', 'SHELTER'))
);
COMMENT ON TABLE data_source IS '외부 수집 대상';

-- 수집 실행
CREATE TABLE collection_run (
    run_id uuid NOT NULL,
    source_id uuid NOT NULL,
    mode varchar(10) NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    status varchar(12) NOT NULL,
    records_received integer NOT NULL DEFAULT 0,
    error_code varchar(80),
    error_message text,
    trigger_type varchar(15) NOT NULL,
    requested_by uuid,
    batch_id uuid,
    heartbeat_at timestamptz,
    records_saved integer NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id),
    UNIQUE (run_id, source_id, mode),
    CHECK (records_received >= 0),
    CHECK (records_saved >= 0),
    CHECK (mode IN ('LIVE','MOCK')),
    CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    CHECK (trigger_type IN ('SCHEDULED', 'ON_DEMAND', 'MANUAL'))
);
COMMENT ON TABLE collection_run IS '수집 실행';

-- 전체 제공처 수동 요청
CREATE TABLE collection_batch (
    batch_id uuid NOT NULL,
    mode varchar(10) NOT NULL,
    requested_by uuid,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    finished_at timestamptz,
    PRIMARY KEY (batch_id),
    CHECK (mode IN ('LIVE','MOCK')),
    CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL_FAILED', 'FAILED'))
);
COMMENT ON TABLE collection_batch IS '전체 제공처 수동 요청';

-- 제공처 작업 임대
CREATE TABLE collection_job_lock (
    source_id uuid NOT NULL,
    mode varchar(10) NOT NULL,
    run_id uuid NOT NULL,
    lease_until timestamptz NOT NULL,
    PRIMARY KEY (source_id, mode),
    CHECK (mode IN ('LIVE','MOCK'))
);
COMMENT ON TABLE collection_job_lock IS '제공처 작업 임대';

-- 통합 재난
CREATE TABLE disaster (
    disaster_id uuid NOT NULL,
    disaster_type_id uuid NOT NULL,
    title varchar(200) NOT NULL,
    occurred_at timestamptz NOT NULL,
    status varchar(15) NOT NULL,
    official_severity_id uuid,
    data_origin varchar(10) NOT NULL,
    updated_at timestamptz NOT NULL,
    ended_at timestamptz,
    PRIMARY KEY (disaster_id),
    CHECK (status NOT IN ('ENDED','LIFTED') OR ended_at IS NOT NULL),
    CHECK (status IN ('OCCURRED', 'ONGOING', 'ENDED', 'LIFTED')),
    CHECK (data_origin IN ('OFFICIAL', 'MOCK'))
);
COMMENT ON TABLE disaster IS '통합 재난';

-- 제공처별 현재 공식 기록
CREATE TABLE source_disaster (
    source_disaster_id uuid NOT NULL,
    disaster_id uuid NOT NULL,
    source_id uuid NOT NULL,
    external_id varchar(255),
    source_record_key varchar(255) NOT NULL,
    source_address text,
    source_url text,
    original_text text NOT NULL,
    source_status varchar(30),
    source_severity_id uuid,
    source_updated_at timestamptz,
    collected_at timestamptz NOT NULL,
    content_hash char(64) NOT NULL,
    occurrence_point geography(Point,4326),
    PRIMARY KEY (source_disaster_id),
    UNIQUE (source_id, source_record_key),
    UNIQUE (source_disaster_id, disaster_id),
    CHECK (occurrence_point IS NULL OR (NOT ST_IsEmpty(occurrence_point::geometry) AND ST_SRID(occurrence_point::geometry) = 4326 AND ST_X(occurrence_point::geometry) BETWEEN -180 AND 180 AND ST_Y(occurrence_point::geometry) BETWEEN -90 AND 90))
);
COMMENT ON TABLE source_disaster IS '제공처별 현재 공식 기록';

-- 공식 원문 버전
CREATE TABLE source_disaster_revision (
    revision_id uuid NOT NULL,
    source_disaster_id uuid NOT NULL,
    revision_no integer NOT NULL,
    original_text text NOT NULL,
    source_address text,
    source_status varchar(30),
    source_severity_id uuid,
    content_hash char(64) NOT NULL,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (revision_id),
    UNIQUE (source_disaster_id, revision_no),
    CHECK (revision_no > 0)
);
COMMENT ON TABLE source_disaster_revision IS '공식 원문 버전';

-- 재난과 행정구역 연결
CREATE TABLE disaster_area (
    disaster_area_id uuid NOT NULL,
    disaster_id uuid NOT NULL,
    area_code varchar(20) NOT NULL,
    area_role varchar(15) NOT NULL,
    basis varchar(20) NOT NULL,
    source_disaster_id uuid NOT NULL,
    derived_at timestamptz NOT NULL,
    PRIMARY KEY (disaster_area_id),
    CHECK (area_role IN ('OCCURRENCE', 'IMPACT')),
    CHECK (basis IN ('OFFICIAL', 'SOURCE_ADDRESS'))
);
COMMENT ON TABLE disaster_area IS '재난과 행정구역 연결';

-- 통합 재난 변경
CREATE TABLE disaster_change (
    change_id uuid NOT NULL,
    disaster_id uuid NOT NULL,
    change_kind varchar(30) NOT NULL,
    old_value jsonb,
    new_value jsonb NOT NULL,
    changed_at timestamptz NOT NULL,
    PRIMARY KEY (change_id)
);
COMMENT ON TABLE disaster_change IS '통합 재난 변경';

-- 날씨 관측
CREATE TABLE weather_observation (
    weather_id uuid NOT NULL,
    source_id uuid NOT NULL,
    area_code varchar(20),
    temperature_c numeric(4,1),
    condition_code varchar(30) NOT NULL,
    observed_at timestamptz NOT NULL,
    collected_at timestamptz NOT NULL,
    grid_x smallint NOT NULL,
    grid_y smallint NOT NULL,
    condition_basis varchar(15) NOT NULL,
    sky_valid_at timestamptz,
    source_payload jsonb NOT NULL,
    PRIMARY KEY (weather_id),
    UNIQUE (source_id, grid_x, grid_y, observed_at),
    CHECK (condition_basis IN ('OBSERVATION', 'FORECAST', 'UNKNOWN'))
);
COMMENT ON TABLE weather_observation IS '날씨 관측';

-- 대피소
CREATE TABLE shelter (
    shelter_id uuid NOT NULL,
    source_id uuid NOT NULL,
    source_record_key varchar(255) NOT NULL,
    name varchar(200) NOT NULL,
    address text,
    point geography(Point,4326),
    source_url text,
    source_updated_at timestamptz,
    collected_at timestamptz NOT NULL,
    additional_info jsonb NOT NULL DEFAULT '{}'::jsonb,
    active boolean NOT NULL,
    shelter_type varchar(30) NOT NULL DEFAULT 'UNKNOWN',
    capacity integer,
    is_underground boolean,
    PRIMARY KEY (shelter_id),
    UNIQUE (source_id, source_record_key),
    CHECK (capacity IS NULL OR capacity >= 0),
    CHECK (point IS NULL OR (NOT ST_IsEmpty(point::geometry) AND ST_SRID(point::geometry) = 4326 AND ST_X(point::geometry) BETWEEN -180 AND 180 AND ST_Y(point::geometry) BETWEEN -90 AND 90)),
    CHECK (shelter_type IN ('EARTHQUAKE_OUTDOOR', 'EARTHQUAKE_TEMPORARY', 'TEMPORARY_HOUSING', 'CIVIL_DEFENSE', 'UNKNOWN'))
);
COMMENT ON TABLE shelter IS '대피소';

-- 공식 행동요령 자료
CREATE TABLE official_resource (
    resource_id uuid NOT NULL,
    agency_name varchar(100) NOT NULL,
    title varchar(255) NOT NULL,
    source_url text NOT NULL,
    version_label varchar(100),
    content_text text,
    checked_at timestamptz NOT NULL,
    PRIMARY KEY (resource_id)
);
COMMENT ON TABLE official_resource IS '공식 행동요령 자료';

-- 유형별 체크리스트
CREATE TABLE checklist_template (
    template_id uuid NOT NULL,
    disaster_type_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    title varchar(150) NOT NULL,
    version integer NOT NULL,
    active boolean NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (template_id),
    UNIQUE (disaster_type_id, version),
    CHECK (version > 0)
);
COMMENT ON TABLE checklist_template IS '유형별 체크리스트';

-- 기본 항목
CREATE TABLE checklist_item (
    item_id uuid NOT NULL,
    template_id uuid NOT NULL,
    item_order smallint NOT NULL,
    content text NOT NULL,
    active boolean NOT NULL,
    PRIMARY KEY (item_id),
    UNIQUE (template_id, item_order),
    CHECK (item_order >= 0)
);
COMMENT ON TABLE checklist_item IS '기본 항목';

-- 회원 완료 상태
CREATE TABLE member_check_item (
    member_id uuid NOT NULL,
    item_id uuid NOT NULL,
    completed_at timestamptz NOT NULL,
    PRIMARY KEY (member_id, item_id)
);
COMMENT ON TABLE member_check_item IS '회원 완료 상태';

-- 회원 알림 설정
CREATE TABLE notification_setting (
    member_id uuid NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    minimum_severity_id uuid,
    allow_after_22 boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (member_id)
);
COMMENT ON TABLE notification_setting IS '회원 알림 설정';

-- FCM 등록 기기
CREATE TABLE device_token (
    device_token_id uuid NOT NULL,
    member_id uuid NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    token_ciphertext text NOT NULL,
    platform varchar(10) NOT NULL,
    active boolean NOT NULL,
    updated_at timestamptz NOT NULL,
    device_id varchar(100) NOT NULL UNIQUE,
    PRIMARY KEY (device_token_id),
    CHECK (platform IN ('ANDROID', 'IOS'))
);
COMMENT ON TABLE device_token IS 'FCM 등록 기기';

-- 회원별 재난 알림
CREATE TABLE notification_event (
    event_id uuid NOT NULL,
    member_id uuid,
    disaster_id uuid,
    event_key varchar(80) NOT NULL,
    event_kind varchar(20) NOT NULL,
    severity_id uuid,
    disaster_title_snapshot varchar(200) NOT NULL,
    created_at timestamptz NOT NULL,
    retention_until timestamptz NOT NULL,
    deidentified_at timestamptz,
    PRIMARY KEY (event_id),
    CHECK (retention_until = created_at + interval '720 hours'),
    CHECK (event_kind IN ('INITIAL', 'SEVERITY_INCREASE'))
);
COMMENT ON TABLE notification_event IS '회원별 재난 알림';

-- 기기별 발송 시도
CREATE TABLE notification_delivery (
    delivery_id uuid NOT NULL,
    event_id uuid NOT NULL,
    device_token_id uuid,
    target_ordinal smallint NOT NULL,
    attempt_no smallint NOT NULL,
    sent_at timestamptz,
    result varchar(12) NOT NULL,
    provider_message_id varchar(255),
    error_code varchar(80),
    PRIMARY KEY (delivery_id),
    UNIQUE (event_id, target_ordinal, attempt_no),
    CHECK (target_ordinal > 0),
    CHECK (attempt_no > 0),
    CHECK (result IN ('PENDING', 'SUCCESS', 'FAILED'))
);
COMMENT ON TABLE notification_delivery IS '기기별 발송 시도';

-- 재난 원문 요약
CREATE TABLE ai_summary (
    summary_id uuid NOT NULL,
    disaster_id uuid NOT NULL,
    source_revision_id uuid NOT NULL,
    status varchar(12) NOT NULL,
    summary_text text,
    model_name varchar(100),
    attempt_count smallint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    PRIMARY KEY (summary_id),
    CHECK (attempt_count >= 0),
    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'STALE'))
);
COMMENT ON TABLE ai_summary IS '재난 원문 요약';

-- 자연어 대응 안내
CREATE TABLE ai_answer (
    answer_id uuid NOT NULL,
    member_id uuid NOT NULL,
    question_text text NOT NULL,
    answer_text text,
    status varchar(15) NOT NULL,
    model_name varchar(100),
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (answer_id),
    CHECK (expires_at = created_at + interval '2160 hours'),
    CHECK (status IN ('COMPLETED', 'INSUFFICIENT', 'FALLBACK', 'FAILED', 'TIMEOUT'))
);
COMMENT ON TABLE ai_answer IS '자연어 대응 안내';

-- AI 답변 공식 근거
CREATE TABLE ai_answer_resource (
    answer_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    PRIMARY KEY (answer_id, resource_id)
);
COMMENT ON TABLE ai_answer_resource IS 'AI 답변 공식 근거';

-- 운영 오류 기록
CREATE TABLE service_error_log (
    error_id uuid NOT NULL,
    run_id uuid,
    component varchar(50) NOT NULL,
    error_code varchar(80) NOT NULL,
    safe_message text NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (error_id)
);
COMMENT ON TABLE service_error_log IS '운영 오류 기록';

-- FK는 모든 테이블 생성 후 추가하여 선언 순서 의존을 제거한다.
ALTER TABLE social_account ADD CONSTRAINT fk_social_account_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE auth_session ADD CONSTRAINT fk_auth_session_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE auth_refresh_history ADD CONSTRAINT fk_auth_refresh_history_session_id
    FOREIGN KEY (session_id) REFERENCES auth_session(session_id) ON DELETE CASCADE;
ALTER TABLE member_role ADD CONSTRAINT fk_member_role_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE admin_area ADD CONSTRAINT fk_admin_area_parent_area_code
    FOREIGN KEY (parent_area_code) REFERENCES admin_area(area_code) ON DELETE NO ACTION;
ALTER TABLE interest_area ADD CONSTRAINT fk_interest_area_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE interest_area ADD CONSTRAINT fk_interest_area_area_code
    FOREIGN KEY (area_code) REFERENCES admin_area(area_code) ON DELETE NO ACTION;
ALTER TABLE member_location_consent ADD CONSTRAINT fk_member_location_consent_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE member_location ADD CONSTRAINT fk_member_location_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE safety_group ADD CONSTRAINT fk_safety_group_created_by
    FOREIGN KEY (created_by) REFERENCES member(member_id) ON DELETE SET NULL;
ALTER TABLE group_member ADD CONSTRAINT fk_group_member_group_id
    FOREIGN KEY (group_id) REFERENCES safety_group(group_id) ON DELETE NO ACTION;
ALTER TABLE group_member ADD CONSTRAINT fk_group_member_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE collection_run ADD CONSTRAINT fk_collection_run_source_id
    FOREIGN KEY (source_id) REFERENCES data_source(source_id) ON DELETE NO ACTION;
ALTER TABLE collection_run ADD CONSTRAINT fk_collection_run_requested_by
    FOREIGN KEY (requested_by) REFERENCES member(member_id) ON DELETE SET NULL;
ALTER TABLE collection_run ADD CONSTRAINT fk_collection_run_batch_id
    FOREIGN KEY (batch_id) REFERENCES collection_batch(batch_id) ON DELETE NO ACTION;
ALTER TABLE collection_batch ADD CONSTRAINT fk_collection_batch_requested_by
    FOREIGN KEY (requested_by) REFERENCES member(member_id) ON DELETE SET NULL;
ALTER TABLE collection_job_lock ADD CONSTRAINT fk_collection_job_lock_source_id
    FOREIGN KEY (source_id) REFERENCES data_source(source_id) ON DELETE NO ACTION;
ALTER TABLE disaster ADD CONSTRAINT fk_disaster_disaster_type_id
    FOREIGN KEY (disaster_type_id) REFERENCES disaster_type(disaster_type_id) ON DELETE NO ACTION;
ALTER TABLE disaster ADD CONSTRAINT fk_disaster_official_severity_id
    FOREIGN KEY (official_severity_id) REFERENCES severity_level(severity_id) ON DELETE NO ACTION;
ALTER TABLE source_disaster ADD CONSTRAINT fk_source_disaster_disaster_id
    FOREIGN KEY (disaster_id) REFERENCES disaster(disaster_id) ON DELETE NO ACTION;
ALTER TABLE source_disaster ADD CONSTRAINT fk_source_disaster_source_id
    FOREIGN KEY (source_id) REFERENCES data_source(source_id) ON DELETE NO ACTION;
ALTER TABLE source_disaster ADD CONSTRAINT fk_source_disaster_source_severity_id
    FOREIGN KEY (source_severity_id) REFERENCES severity_level(severity_id) ON DELETE NO ACTION;
ALTER TABLE source_disaster_revision ADD CONSTRAINT fk_source_disaster_revision_source_disaster_id
    FOREIGN KEY (source_disaster_id) REFERENCES source_disaster(source_disaster_id) ON DELETE NO ACTION;
ALTER TABLE source_disaster_revision ADD CONSTRAINT fk_source_disaster_revision_source_severity_id
    FOREIGN KEY (source_severity_id) REFERENCES severity_level(severity_id) ON DELETE NO ACTION;
ALTER TABLE disaster_area ADD CONSTRAINT fk_disaster_area_disaster_id
    FOREIGN KEY (disaster_id) REFERENCES disaster(disaster_id) ON DELETE NO ACTION;
ALTER TABLE disaster_area ADD CONSTRAINT fk_disaster_area_area_code
    FOREIGN KEY (area_code) REFERENCES admin_area(area_code) ON DELETE NO ACTION;
ALTER TABLE disaster_area ADD CONSTRAINT fk_disaster_area_source_disaster_id
    FOREIGN KEY (source_disaster_id) REFERENCES source_disaster(source_disaster_id) ON DELETE NO ACTION;
ALTER TABLE disaster_change ADD CONSTRAINT fk_disaster_change_disaster_id
    FOREIGN KEY (disaster_id) REFERENCES disaster(disaster_id) ON DELETE NO ACTION;
ALTER TABLE weather_observation ADD CONSTRAINT fk_weather_observation_source_id
    FOREIGN KEY (source_id) REFERENCES data_source(source_id) ON DELETE NO ACTION;
ALTER TABLE weather_observation ADD CONSTRAINT fk_weather_observation_area_code
    FOREIGN KEY (area_code) REFERENCES admin_area(area_code) ON DELETE NO ACTION;
ALTER TABLE shelter ADD CONSTRAINT fk_shelter_source_id
    FOREIGN KEY (source_id) REFERENCES data_source(source_id) ON DELETE NO ACTION;
ALTER TABLE checklist_template ADD CONSTRAINT fk_checklist_template_disaster_type_id
    FOREIGN KEY (disaster_type_id) REFERENCES disaster_type(disaster_type_id) ON DELETE NO ACTION;
ALTER TABLE checklist_template ADD CONSTRAINT fk_checklist_template_resource_id
    FOREIGN KEY (resource_id) REFERENCES official_resource(resource_id) ON DELETE NO ACTION;
ALTER TABLE checklist_item ADD CONSTRAINT fk_checklist_item_template_id
    FOREIGN KEY (template_id) REFERENCES checklist_template(template_id) ON DELETE NO ACTION;
ALTER TABLE member_check_item ADD CONSTRAINT fk_member_check_item_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE member_check_item ADD CONSTRAINT fk_member_check_item_item_id
    FOREIGN KEY (item_id) REFERENCES checklist_item(item_id) ON DELETE NO ACTION;
ALTER TABLE notification_setting ADD CONSTRAINT fk_notification_setting_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE notification_setting ADD CONSTRAINT fk_notification_setting_minimum_severity_id
    FOREIGN KEY (minimum_severity_id) REFERENCES severity_level(severity_id) ON DELETE NO ACTION;
ALTER TABLE device_token ADD CONSTRAINT fk_device_token_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE notification_event ADD CONSTRAINT fk_notification_event_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE SET NULL;
ALTER TABLE notification_event ADD CONSTRAINT fk_notification_event_disaster_id
    FOREIGN KEY (disaster_id) REFERENCES disaster(disaster_id) ON DELETE SET NULL;
ALTER TABLE notification_event ADD CONSTRAINT fk_notification_event_severity_id
    FOREIGN KEY (severity_id) REFERENCES severity_level(severity_id) ON DELETE NO ACTION;
ALTER TABLE notification_delivery ADD CONSTRAINT fk_notification_delivery_event_id
    FOREIGN KEY (event_id) REFERENCES notification_event(event_id) ON DELETE CASCADE;
ALTER TABLE notification_delivery ADD CONSTRAINT fk_notification_delivery_device_token_id
    FOREIGN KEY (device_token_id) REFERENCES device_token(device_token_id) ON DELETE SET NULL;
ALTER TABLE ai_summary ADD CONSTRAINT fk_ai_summary_disaster_id
    FOREIGN KEY (disaster_id) REFERENCES disaster(disaster_id) ON DELETE NO ACTION;
ALTER TABLE ai_summary ADD CONSTRAINT fk_ai_summary_source_revision_id
    FOREIGN KEY (source_revision_id) REFERENCES source_disaster_revision(revision_id) ON DELETE NO ACTION;
ALTER TABLE ai_answer ADD CONSTRAINT fk_ai_answer_member_id
    FOREIGN KEY (member_id) REFERENCES member(member_id) ON DELETE NO ACTION;
ALTER TABLE ai_answer_resource ADD CONSTRAINT fk_ai_answer_resource_answer_id
    FOREIGN KEY (answer_id) REFERENCES ai_answer(answer_id) ON DELETE CASCADE;
ALTER TABLE ai_answer_resource ADD CONSTRAINT fk_ai_answer_resource_resource_id
    FOREIGN KEY (resource_id) REFERENCES official_resource(resource_id) ON DELETE NO ACTION;
ALTER TABLE service_error_log ADD CONSTRAINT fk_service_error_log_run_id
    FOREIGN KEY (run_id) REFERENCES collection_run(run_id) ON DELETE SET NULL;
ALTER TABLE disaster_area ADD CONSTRAINT fk_disaster_area_source_membership FOREIGN KEY (source_disaster_id, disaster_id) REFERENCES source_disaster(source_disaster_id, disaster_id);
ALTER TABLE collection_job_lock ADD CONSTRAINT fk_collection_job_lock_owner FOREIGN KEY (run_id, source_id, mode) REFERENCES collection_run(run_id, source_id, mode);

-- 원문 버전이 해당 재난 소속인지 확인한다. 버전의 부모 소속 변경도 차단한다.
CREATE FUNCTION check_ai_summary_disaster() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'ai_summary' THEN
        PERFORM 1 FROM source_disaster_revision r
        JOIN source_disaster s USING (source_disaster_id)
        WHERE r.revision_id = NEW.source_revision_id AND s.disaster_id = NEW.disaster_id
        FOR SHARE OF r, s;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'AI summary disaster does not match source revision' USING ERRCODE = '23514';
        END IF;
    ELSE
        -- Reparenting immutable source records requires an explicit migration.
        IF TG_TABLE_NAME = 'source_disaster' THEN
            IF NEW.disaster_id IS DISTINCT FROM OLD.disaster_id THEN
                RAISE EXCEPTION 'Source disaster parent is immutable' USING ERRCODE = '23514';
            END IF;
        ELSIF TG_TABLE_NAME = 'source_disaster_revision' THEN
            IF NEW.source_disaster_id IS DISTINCT FROM OLD.source_disaster_id THEN
                RAISE EXCEPTION 'Source revision parent is immutable' USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_ai_summary_disaster BEFORE INSERT OR UPDATE ON ai_summary
    FOR EACH ROW EXECUTE FUNCTION check_ai_summary_disaster();
CREATE TRIGGER trg_source_disaster_parent BEFORE UPDATE OF disaster_id ON source_disaster
    FOR EACH ROW EXECUTE FUNCTION check_ai_summary_disaster();
CREATE TRIGGER trg_source_revision_parent BEFORE UPDATE OF source_disaster_id ON source_disaster_revision
    FOR EACH ROW EXECUTE FUNCTION check_ai_summary_disaster();

-- 부분 고유성 및 조회/만료/공간 인덱스
CREATE UNIQUE INDEX uq_group_single_admin ON group_member(group_id) WHERE role = 'ADMIN';
CREATE UNIQUE INDEX uq_checklist_active_template ON checklist_template(disaster_type_id) WHERE active = true;
CREATE UNIQUE INDEX uq_notification_event_active ON notification_event(member_id, disaster_id, event_key) WHERE member_id IS NOT NULL;
CREATE INDEX ix_member_expired_withdrawal ON member(withdrawn_at) WHERE status = 'WITHDRAWAL_PENDING';
CREATE INDEX ix_group_member_member ON group_member(member_id);
CREATE INDEX ix_auth_session_member ON auth_session(member_id);
CREATE INDEX ix_auth_refresh_history_session ON auth_refresh_history(session_id);
CREATE INDEX ix_disaster_status_occurred ON disaster(status, occurred_at DESC);
CREATE INDEX ix_disaster_occurred ON disaster(occurred_at DESC, disaster_id DESC);
CREATE INDEX ix_disaster_area_area_role ON disaster_area(area_code, area_role);
CREATE INDEX ix_disaster_area_disaster ON disaster_area(disaster_id, area_role, basis);
CREATE INDEX ix_source_disaster_disaster ON source_disaster(disaster_id);
CREATE INDEX ix_collection_run_source_finished ON collection_run(source_id, finished_at DESC);
CREATE INDEX ix_collection_run_batch ON collection_run(batch_id);
CREATE INDEX ix_notification_event_member_created ON notification_event(member_id, created_at DESC);
CREATE INDEX ix_notification_event_retention ON notification_event(retention_until);
CREATE INDEX ix_ai_answer_expires ON ai_answer(expires_at);
CREATE INDEX ix_service_error_log_occurred ON service_error_log(occurred_at DESC, error_id DESC);
CREATE INDEX ix_admin_area_boundary ON admin_area USING GIST(boundary);
CREATE INDEX ix_shelter_point ON shelter USING GIST(point);
CREATE INDEX ix_source_disaster_occurrence_point ON source_disaster USING GIST(occurrence_point);
CREATE INDEX ix_member_location_point ON member_location USING GIST(point);

-- 설계서 컬럼 설명
COMMENT ON COLUMN member.member_id IS '서비스 내부 식별자';
COMMENT ON COLUMN member.display_name IS '표시 이름';
COMMENT ON COLUMN member.status IS 'ACTIVE 또는 WITHDRAWAL_PENDING';
COMMENT ON COLUMN member.created_at IS '가입 시각';
COMMENT ON COLUMN member.withdrawn_at IS '탈퇴 요청 시각; ACTIVE는 NULL, 탈퇴 취소 시 NULL로 초기화';
COMMENT ON COLUMN social_account.social_account_id IS '계정 연결 ID';
COMMENT ON COLUMN social_account.member_id IS 'member.member_id';
COMMENT ON COLUMN social_account.provider IS 'GOOGLE, KAKAO 등';
COMMENT ON COLUMN social_account.provider_user_id IS '제공자의 사용자 ID';
COMMENT ON COLUMN social_account.linked_at IS '연결 시각';
COMMENT ON COLUMN auth_session.session_id IS '세션 ID';
COMMENT ON COLUMN auth_session.member_id IS 'member.member_id';
COMMENT ON COLUMN auth_session.refresh_token_hash IS '갱신 토큰 해시';
COMMENT ON COLUMN auth_session.created_at IS '발급 시각';
COMMENT ON COLUMN auth_session.expires_at IS '만료 시각';
COMMENT ON COLUMN auth_session.revoked_at IS '로그아웃·폐기 시각';
COMMENT ON COLUMN auth_refresh_history.token_hash IS '이미 사용한 refresh hash, 원문 저장 금지';
COMMENT ON COLUMN auth_refresh_history.session_id IS 'auth_session.session_id';
COMMENT ON COLUMN auth_refresh_history.used_at IS '회전으로 사용 완료된 시각';
COMMENT ON COLUMN auth_refresh_history.expires_at IS '해당 세션의 고정 만료시각';
COMMENT ON COLUMN member_role.member_id IS 'member.member_id';
COMMENT ON COLUMN member_role.role IS 'USER 또는 OPS';
COMMENT ON COLUMN member_role.granted_at IS '서버 권한 부여 시각';
COMMENT ON COLUMN admin_area.area_code IS '행정구역 코드';
COMMENT ON COLUMN admin_area.name IS '지역명';
COMMENT ON COLUMN admin_area.level IS 'SIDO, SIGUNGU 등';
COMMENT ON COLUMN admin_area.parent_area_code IS '상위 admin_area.area_code';
COMMENT ON COLUMN admin_area.center_point IS '지도 중심';
COMMENT ON COLUMN admin_area.active IS '현재 유효한 지역 여부';
COMMENT ON COLUMN admin_area.boundary IS '공식 행정경계. 여러 면과 내부 구멍을 보존하며 없으면 지도 영역/공간 판정 불가';
COMMENT ON COLUMN admin_area.boundary_source_url IS '경계 원천 및 재사용 근거';
COMMENT ON COLUMN admin_area.boundary_source_updated_at IS '확인 가능한 원천 갱신 시각';
COMMENT ON COLUMN admin_area.boundary_collected_at IS '경계 수집 시각';
COMMENT ON COLUMN admin_area.boundary_source_provider IS '초기 SGIS. 원천 코드 체계 구분';
COMMENT ON COLUMN admin_area.boundary_source_area_code IS 'crosswalk로 검증한 원천 코드. 복수 코드는 관련 원천 파일의 crosswalk에 보존';
COMMENT ON COLUMN admin_area.boundary_base_year IS '경계 기준년도. collected_at 또는 source_updated_at과 구분';
COMMENT ON COLUMN interest_area.interest_area_id IS '등록 ID';
COMMENT ON COLUMN interest_area.member_id IS 'member.member_id';
COMMENT ON COLUMN interest_area.area_code IS 'admin_area.area_code';
COMMENT ON COLUMN interest_area.display_name IS '사용자 표시명';
COMMENT ON COLUMN interest_area.notify_enabled IS '알림 대상 선택, 기본 true';
COMMENT ON COLUMN interest_area.created_at IS '등록 시각';
COMMENT ON COLUMN member_location_consent.member_id IS 'member.member_id';
COMMENT ON COLUMN member_location_consent.consented IS '기본 false, 좌표 없이 변경 가능';
COMMENT ON COLUMN member_location_consent.changed_at IS '서버 동의 변경 시각';
COMMENT ON COLUMN member_location.member_id IS 'member.member_id; 회원당 한 행';
COMMENT ON COLUMN member_location.location_mode IS 'GPS만 저장. 직접 지정은 요청 기준으로 사용';
COMMENT ON COLUMN member_location.point IS '동의 기반 최신 GPS 좌표, SRID 4326';
COMMENT ON COLUMN member_location.updated_at IS '위치 갱신 시각';
COMMENT ON COLUMN safety_group.group_id IS '그룹 ID';
COMMENT ON COLUMN safety_group.name IS '그룹 이름';
COMMENT ON COLUMN safety_group.category IS 'FAMILY/FRIEND/OTHER, 기본 OTHER';
COMMENT ON COLUMN safety_group.created_by IS '최초 생성자 member.member_id';
COMMENT ON COLUMN safety_group.invite_code IS '영구 초대 코드';
COMMENT ON COLUMN safety_group.created_at IS '생성 시각';
COMMENT ON COLUMN group_member.group_id IS 'safety_group.group_id';
COMMENT ON COLUMN group_member.member_id IS 'member.member_id';
COMMENT ON COLUMN group_member.role IS 'ADMIN 또는 MEMBER';
COMMENT ON COLUMN group_member.location_sharing_enabled IS '해당 그룹에 대한 공유 동의, 기본 false';
COMMENT ON COLUMN group_member.joined_at IS '참여 시각';
COMMENT ON COLUMN disaster_type.disaster_type_id IS '유형 ID';
COMMENT ON COLUMN disaster_type.code IS 'EARTHQUAKE, HEAVY_RAIN, WILDFIRE 등';
COMMENT ON COLUMN disaster_type.name IS '표시명';
COMMENT ON COLUMN disaster_type.active IS '사용 여부';
COMMENT ON COLUMN severity_level.severity_id IS '등급 ID';
COMMENT ON COLUMN severity_level.code IS '등급 코드';
COMMENT ON COLUMN severity_level.label IS '표시명';
COMMENT ON COLUMN severity_level.rank IS '높을수록 심각한 비교값';
COMMENT ON COLUMN data_source.source_id IS '제공처·API ID';
COMMENT ON COLUMN data_source.name IS '표시명';
COMMENT ON COLUMN data_source.source_kind IS 'DISASTER, WEATHER, SHELTER';
COMMENT ON COLUMN data_source.endpoint_key IS '서버 설정 참조 키';
COMMENT ON COLUMN data_source.collection_interval_minutes IS '자동 수집 주기; 수동 갱신이면 NULL';
COMMENT ON COLUMN data_source.active IS '수집 사용 여부';
COMMENT ON COLUMN collection_run.run_id IS '실행 ID';
COMMENT ON COLUMN collection_run.source_id IS 'data_source.source_id';
COMMENT ON COLUMN collection_run.mode IS 'LIVE 또는 MOCK';
COMMENT ON COLUMN collection_run.started_at IS '시작 시각';
COMMENT ON COLUMN collection_run.finished_at IS '종료 시각';
COMMENT ON COLUMN collection_run.status IS 'RUNNING, SUCCESS, FAILED';
COMMENT ON COLUMN collection_run.records_received IS '수신 건수, 기본 0';
COMMENT ON COLUMN collection_run.error_code IS '실패 코드';
COMMENT ON COLUMN collection_run.error_message IS '민감정보를 제외한 오류 설명';
COMMENT ON COLUMN collection_run.trigger_type IS 'SCHEDULED, ON_DEMAND, MANUAL';
COMMENT ON COLUMN collection_run.requested_by IS '요청 member, 삭제 시 SET NULL';
COMMENT ON COLUMN collection_run.batch_id IS 'collection_batch.batch_id';
COMMENT ON COLUMN collection_run.heartbeat_at IS 'worker 마지막 heartbeat';
COMMENT ON COLUMN collection_run.records_saved IS '실제 저장 건수, 기본 0';
COMMENT ON COLUMN collection_batch.batch_id IS '전체 요청 UUID';
COMMENT ON COLUMN collection_batch.mode IS 'LIVE 또는 MOCK';
COMMENT ON COLUMN collection_batch.requested_by IS '요청 member, 삭제 시 SET NULL';
COMMENT ON COLUMN collection_batch.status IS 'RUNNING, SUCCESS, PARTIAL_FAILED, FAILED';
COMMENT ON COLUMN collection_batch.created_at IS '접수 시각';
COMMENT ON COLUMN collection_batch.finished_at IS '자식 실행 종료 시각';
COMMENT ON COLUMN collection_job_lock.source_id IS 'data_source.source_id';
COMMENT ON COLUMN collection_job_lock.mode IS 'LIVE 또는 MOCK';
COMMENT ON COLUMN collection_job_lock.run_id IS '현재 소유 collection_run';
COMMENT ON COLUMN collection_job_lock.lease_until IS '임대 만료시각';
COMMENT ON COLUMN disaster.disaster_id IS '재난 ID';
COMMENT ON COLUMN disaster.disaster_type_id IS 'disaster_type';
COMMENT ON COLUMN disaster.title IS '통합 표시 제목';
COMMENT ON COLUMN disaster.occurred_at IS '발생 시각';
COMMENT ON COLUMN disaster.status IS 'OCCURRED, ONGOING, ENDED, LIFTED';
COMMENT ON COLUMN disaster.official_severity_id IS 'severity_level; 공식 위험등급';
COMMENT ON COLUMN disaster.data_origin IS 'OFFICIAL 또는 MOCK';
COMMENT ON COLUMN disaster.updated_at IS '최근 변경';
COMMENT ON COLUMN disaster.ended_at IS '종료·해제 시각';
COMMENT ON COLUMN source_disaster.source_disaster_id IS '제공처 기록 ID';
COMMENT ON COLUMN source_disaster.disaster_id IS 'disaster.disaster_id';
COMMENT ON COLUMN source_disaster.source_id IS 'data_source.source_id';
COMMENT ON COLUMN source_disaster.external_id IS '실제 원본 식별자';
COMMENT ON COLUMN source_disaster.source_record_key IS '중복 판별 키';
COMMENT ON COLUMN source_disaster.source_address IS '재난 발생지 원본 주소';
COMMENT ON COLUMN source_disaster.source_url IS '원문 링크';
COMMENT ON COLUMN source_disaster.original_text IS '현재 공식 원문';
COMMENT ON COLUMN source_disaster.source_status IS '원본 상태';
COMMENT ON COLUMN source_disaster.source_severity_id IS 'severity_level';
COMMENT ON COLUMN source_disaster.source_updated_at IS '제공처 갱신 시각';
COMMENT ON COLUMN source_disaster.collected_at IS '최종 수집 시각';
COMMENT ON COLUMN source_disaster.content_hash IS '내용 변경 판별';
COMMENT ON COLUMN source_disaster.occurrence_point IS '원본 공식 발생 좌표만 저장. 원본 주소 지오코딩 결과를 공식 좌표로 넣지 않음';
COMMENT ON COLUMN source_disaster_revision.revision_id IS '버전 ID';
COMMENT ON COLUMN source_disaster_revision.source_disaster_id IS 'source_disaster';
COMMENT ON COLUMN source_disaster_revision.revision_no IS '제공처 기록별 순번';
COMMENT ON COLUMN source_disaster_revision.original_text IS '해당 버전 원문';
COMMENT ON COLUMN source_disaster_revision.source_address IS '해당 버전 주소';
COMMENT ON COLUMN source_disaster_revision.source_status IS '해당 버전 상태';
COMMENT ON COLUMN source_disaster_revision.source_severity_id IS 'severity_level';
COMMENT ON COLUMN source_disaster_revision.content_hash IS '버전 해시';
COMMENT ON COLUMN source_disaster_revision.observed_at IS '수집 시각';
COMMENT ON COLUMN disaster_area.disaster_area_id IS '연결 ID';
COMMENT ON COLUMN disaster_area.disaster_id IS 'disaster';
COMMENT ON COLUMN disaster_area.area_code IS 'admin_area';
COMMENT ON COLUMN disaster_area.area_role IS 'OCCURRENCE 또는 IMPACT';
COMMENT ON COLUMN disaster_area.basis IS 'OFFICIAL 또는 SOURCE_ADDRESS';
COMMENT ON COLUMN disaster_area.source_disaster_id IS '판정 근거 원문';
COMMENT ON COLUMN disaster_area.derived_at IS '지역 판정 시각';
COMMENT ON COLUMN disaster_change.change_id IS '변경 ID';
COMMENT ON COLUMN disaster_change.disaster_id IS 'disaster';
COMMENT ON COLUMN disaster_change.change_kind IS 'STATUS, SEVERITY, TITLE, AREA 등';
COMMENT ON COLUMN disaster_change.old_value IS '변경 전';
COMMENT ON COLUMN disaster_change.new_value IS '변경 후';
COMMENT ON COLUMN disaster_change.changed_at IS '변경 시각';
COMMENT ON COLUMN weather_observation.weather_id IS '관측 ID';
COMMENT ON COLUMN weather_observation.source_id IS 'data_source';
COMMENT ON COLUMN weather_observation.area_code IS 'admin_area, 좌표 격자 조회는 NULL 가능';
COMMENT ON COLUMN weather_observation.temperature_c IS '섭씨 기온';
COMMENT ON COLUMN weather_observation.condition_code IS '기상 상태 코드';
COMMENT ON COLUMN weather_observation.observed_at IS '기준 시각';
COMMENT ON COLUMN weather_observation.collected_at IS '수집 시각';
COMMENT ON COLUMN weather_observation.grid_x IS '기상청 nx';
COMMENT ON COLUMN weather_observation.grid_y IS '기상청 ny';
COMMENT ON COLUMN weather_observation.condition_basis IS 'OBSERVATION, FORECAST, UNKNOWN';
COMMENT ON COLUMN weather_observation.sky_valid_at IS '예보 하늘 상태 유효시각';
COMMENT ON COLUMN weather_observation.source_payload IS '안전한 원본 category 값, 비밀키/사용자 좌표 제외';
COMMENT ON COLUMN shelter.shelter_id IS '내부 ID';
COMMENT ON COLUMN shelter.source_id IS 'data_source';
COMMENT ON COLUMN shelter.source_record_key IS '원본 판별 키';
COMMENT ON COLUMN shelter.name IS '원본 명칭';
COMMENT ON COLUMN shelter.address IS '원본 주소';
COMMENT ON COLUMN shelter.point IS '원본 좌표';
COMMENT ON COLUMN shelter.source_url IS '원천 링크';
COMMENT ON COLUMN shelter.source_updated_at IS '원본 갱신 시각';
COMMENT ON COLUMN shelter.collected_at IS '수집 시각';
COMMENT ON COLUMN shelter.additional_info IS '제공처가 준 추가 항목, 기본 {}';
COMMENT ON COLUMN shelter.active IS '조회 대상 여부';
COMMENT ON COLUMN shelter.shelter_type IS 'EARTHQUAKE_OUTDOOR/EARTHQUAKE_TEMPORARY/TEMPORARY_HOUSING/CIVIL_DEFENSE/UNKNOWN. 기본 UNKNOWN';
COMMENT ON COLUMN shelter.capacity IS '원본 수용인원, CHECK capacity >= 0';
COMMENT ON COLUMN shelter.is_underground IS '원본 지하 여부. 미제공은 NULL';
COMMENT ON COLUMN official_resource.resource_id IS '자료 ID';
COMMENT ON COLUMN official_resource.agency_name IS '공식 기관';
COMMENT ON COLUMN official_resource.title IS '제목';
COMMENT ON COLUMN official_resource.source_url IS '공식 링크';
COMMENT ON COLUMN official_resource.version_label IS '발행·버전 정보';
COMMENT ON COLUMN official_resource.content_text IS '검색 대상 내용';
COMMENT ON COLUMN official_resource.checked_at IS '확인 시각';
COMMENT ON COLUMN checklist_template.template_id IS '템플릿 ID';
COMMENT ON COLUMN checklist_template.disaster_type_id IS 'disaster_type';
COMMENT ON COLUMN checklist_template.resource_id IS 'official_resource';
COMMENT ON COLUMN checklist_template.title IS '템플릿명';
COMMENT ON COLUMN checklist_template.version IS '버전';
COMMENT ON COLUMN checklist_template.active IS '현재 사용 여부';
COMMENT ON COLUMN checklist_template.published_at IS '게시 시각';
COMMENT ON COLUMN checklist_item.item_id IS '항목 ID';
COMMENT ON COLUMN checklist_item.template_id IS 'checklist_template';
COMMENT ON COLUMN checklist_item.item_order IS '정렬 순서';
COMMENT ON COLUMN checklist_item.content IS '항목 내용';
COMMENT ON COLUMN checklist_item.active IS '사용 여부';
COMMENT ON COLUMN member_check_item.member_id IS 'member';
COMMENT ON COLUMN member_check_item.item_id IS 'checklist_item';
COMMENT ON COLUMN member_check_item.completed_at IS '완료 시각';
COMMENT ON COLUMN notification_setting.member_id IS 'member';
COMMENT ON COLUMN notification_setting.enabled IS '수신 여부, 기본 false';
COMMENT ON COLUMN notification_setting.minimum_severity_id IS 'severity_level; NULL은 등급 제한 없음';
COMMENT ON COLUMN notification_setting.allow_after_22 IS '22시 이후 허용, 기본 false';
COMMENT ON COLUMN notification_setting.updated_at IS '변경 시각';
COMMENT ON COLUMN device_token.device_token_id IS '기기 등록 ID';
COMMENT ON COLUMN device_token.member_id IS 'member';
COMMENT ON COLUMN device_token.token_hash IS '중복 검사용 해시';
COMMENT ON COLUMN device_token.token_ciphertext IS '암호화된 발송 토큰';
COMMENT ON COLUMN device_token.platform IS 'ANDROID 또는 IOS';
COMMENT ON COLUMN device_token.active IS '발송 가능 여부';
COMMENT ON COLUMN device_token.updated_at IS '갱신 시각';
COMMENT ON COLUMN device_token.device_id IS '앱 설치 식별 문자열, 회원 간 덮어쓰기 금지';
COMMENT ON COLUMN notification_event.event_id IS '알림 ID';
COMMENT ON COLUMN notification_event.member_id IS 'member; 탈퇴 시 NULL';
COMMENT ON COLUMN notification_event.disaster_id IS 'disaster; 재난 삭제 시 NULL';
COMMENT ON COLUMN notification_event.event_key IS 'INITIAL 또는 SEVERITY:등급';
COMMENT ON COLUMN notification_event.event_kind IS 'INITIAL 또는 SEVERITY_INCREASE';
COMMENT ON COLUMN notification_event.severity_id IS '발송 당시 등급';
COMMENT ON COLUMN notification_event.disaster_title_snapshot IS '당시 제목; 개인정보 제외';
COMMENT ON COLUMN notification_event.created_at IS '이벤트 생성 시각';
COMMENT ON COLUMN notification_event.retention_until IS 'created_at + 30일';
COMMENT ON COLUMN notification_event.deidentified_at IS '탈퇴 후 비식별 처리 시각';
COMMENT ON COLUMN notification_delivery.delivery_id IS '시도 ID';
COMMENT ON COLUMN notification_delivery.event_id IS 'notification_event';
COMMENT ON COLUMN notification_delivery.device_token_id IS 'device_token; 탈퇴 시 NULL';
COMMENT ON COLUMN notification_delivery.target_ordinal IS '이벤트 안의 비식별 기기 순번';
COMMENT ON COLUMN notification_delivery.attempt_no IS '재시도 번호';
COMMENT ON COLUMN notification_delivery.sent_at IS 'FCM 요청 시각';
COMMENT ON COLUMN notification_delivery.result IS 'PENDING, SUCCESS, FAILED';
COMMENT ON COLUMN notification_delivery.provider_message_id IS 'FCM 응답 ID; 탈퇴 시 삭제';
COMMENT ON COLUMN notification_delivery.error_code IS '실패 코드';
COMMENT ON COLUMN ai_summary.summary_id IS '요약 ID';
COMMENT ON COLUMN ai_summary.disaster_id IS 'disaster';
COMMENT ON COLUMN ai_summary.source_revision_id IS 'source_disaster_revision';
COMMENT ON COLUMN ai_summary.status IS 'PENDING, COMPLETED, FAILED, STALE';
COMMENT ON COLUMN ai_summary.summary_text IS 'AI 요약';
COMMENT ON COLUMN ai_summary.model_name IS '사용 모델';
COMMENT ON COLUMN ai_summary.attempt_count IS '재시도 횟수, 기본 0';
COMMENT ON COLUMN ai_summary.created_at IS '작업 생성';
COMMENT ON COLUMN ai_summary.completed_at IS '처리 완료';
COMMENT ON COLUMN ai_answer.answer_id IS '답변 ID';
COMMENT ON COLUMN ai_answer.member_id IS '요청 회원';
COMMENT ON COLUMN ai_answer.question_text IS '자연어 질문';
COMMENT ON COLUMN ai_answer.answer_text IS '답변 또는 기본 행동요령';
COMMENT ON COLUMN ai_answer.status IS 'COMPLETED, INSUFFICIENT, FALLBACK, FAILED, TIMEOUT';
COMMENT ON COLUMN ai_answer.model_name IS '사용 모델';
COMMENT ON COLUMN ai_answer.created_at IS '요청 시각';
COMMENT ON COLUMN ai_answer.completed_at IS '완료 시각';
COMMENT ON COLUMN ai_answer.expires_at IS 'created_at + 90일';
COMMENT ON COLUMN ai_answer_resource.answer_id IS 'ai_answer';
COMMENT ON COLUMN ai_answer_resource.resource_id IS 'official_resource';
COMMENT ON COLUMN service_error_log.error_id IS '오류 ID';
COMMENT ON COLUMN service_error_log.run_id IS 'collection_run; 수집 오류인 경우';
COMMENT ON COLUMN service_error_log.component IS '수집, 알림, AI 등';
COMMENT ON COLUMN service_error_log.error_code IS '오류 코드';
COMMENT ON COLUMN service_error_log.safe_message IS '민감정보 제외 설명';
COMMENT ON COLUMN service_error_log.occurred_at IS '발생 시각';


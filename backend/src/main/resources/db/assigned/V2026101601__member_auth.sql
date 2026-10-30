CREATE TABLE member (
 member_id uuid PRIMARY KEY, display_name varchar(50) NOT NULL,
 status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','WITHDRAWAL_PENDING')),
 created_at timestamptz NOT NULL, withdrawn_at timestamptz,
 CHECK ((status='ACTIVE' AND withdrawn_at IS NULL) OR (status='WITHDRAWAL_PENDING' AND withdrawn_at IS NOT NULL))
);
CREATE TABLE social_account (
 social_account_id uuid PRIMARY KEY, member_id uuid NOT NULL REFERENCES member(member_id),
 provider varchar(20) NOT NULL CHECK(provider IN ('GOOGLE','KAKAO')),
 provider_user_id varchar(255) NOT NULL, linked_at timestamptz NOT NULL,
 UNIQUE(provider,provider_user_id)
);
CREATE TABLE auth_session (
 session_id uuid PRIMARY KEY, member_id uuid NOT NULL REFERENCES member(member_id),
 refresh_token_hash char(64) UNIQUE NOT NULL, created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL, revoked_at timestamptz, CHECK(expires_at>created_at)
);
CREATE INDEX ix_auth_session_member ON auth_session(member_id);
CREATE TABLE auth_refresh_history (
 token_hash char(64) PRIMARY KEY, session_id uuid NOT NULL REFERENCES auth_session(session_id),
 used_at timestamptz NOT NULL, expires_at timestamptz NOT NULL
);
CREATE TABLE member_role (
 member_id uuid NOT NULL REFERENCES member(member_id), role varchar(10) NOT NULL CHECK(role IN ('USER','OPS')),
 granted_at timestamptz NOT NULL, PRIMARY KEY(member_id,role)
);
CREATE INDEX ix_member_expired_withdrawal ON member(withdrawn_at) WHERE status='WITHDRAWAL_PENDING';

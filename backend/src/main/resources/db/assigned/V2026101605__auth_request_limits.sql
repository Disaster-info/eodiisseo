-- Operational support for the documented invitation-attempt limit; contains no tokens or raw codes.
CREATE TABLE auth_request_window (
 member_id uuid NOT NULL REFERENCES member(member_id) ON DELETE CASCADE,
 scope varchar(30) NOT NULL, window_start timestamptz NOT NULL,
 request_count integer NOT NULL CHECK(request_count>0), PRIMARY KEY(member_id,scope,window_start)
);
CREATE INDEX ix_auth_request_window_expiry ON auth_request_window(window_start);

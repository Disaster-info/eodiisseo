CREATE TABLE safety_group (
 group_id uuid PRIMARY KEY, name varchar(80) NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 80),
 category varchar(30) NOT NULL DEFAULT 'OTHER' CHECK(category IN ('FAMILY','FRIEND','OTHER')),
 created_by uuid REFERENCES member(member_id) ON DELETE SET NULL,
 invite_code varchar(32) UNIQUE NOT NULL, created_at timestamptz NOT NULL
);
CREATE TABLE group_member (
 group_id uuid NOT NULL REFERENCES safety_group(group_id), member_id uuid NOT NULL REFERENCES member(member_id),
 role varchar(10) NOT NULL CHECK(role IN ('ADMIN','MEMBER')),
 location_sharing_enabled boolean NOT NULL DEFAULT false, joined_at timestamptz NOT NULL,
 PRIMARY KEY(group_id,member_id)
);
CREATE UNIQUE INDEX uq_group_single_admin ON group_member(group_id) WHERE role='ADMIN';
CREATE INDEX ix_group_member_member ON group_member(member_id);

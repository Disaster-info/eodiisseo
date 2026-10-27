-- Prerequisite: the common-schema owners provide disaster_type and official_resource first.
-- This migration deliberately does not create another owner's tables or invent official content.
CREATE TABLE checklist_template (
 template_id uuid PRIMARY KEY, disaster_type_id uuid NOT NULL REFERENCES disaster_type(disaster_type_id),
 resource_id uuid NOT NULL REFERENCES official_resource(resource_id), title varchar(150) NOT NULL,
 version integer NOT NULL CHECK(version>0), active boolean NOT NULL, published_at timestamptz,
 UNIQUE(disaster_type_id,version)
);
CREATE UNIQUE INDEX uq_checklist_active_template ON checklist_template(disaster_type_id) WHERE active=true;
CREATE TABLE checklist_item (
 item_id uuid PRIMARY KEY, template_id uuid NOT NULL REFERENCES checklist_template(template_id),
 item_order smallint NOT NULL CHECK(item_order>=0), content text NOT NULL, active boolean NOT NULL,
 UNIQUE(template_id,item_order)
);
CREATE TABLE member_check_item (
 member_id uuid NOT NULL REFERENCES member(member_id), item_id uuid NOT NULL REFERENCES checklist_item(item_id),
 completed_at timestamptz NOT NULL, PRIMARY KEY(member_id,item_id)
);

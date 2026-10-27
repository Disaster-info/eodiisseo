-- Test-only contracts for foreign-owned schemas. These are not production migrations or official data.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE TABLE disaster_type(disaster_type_id uuid PRIMARY KEY,code varchar(30) UNIQUE NOT NULL,name varchar(50),active boolean DEFAULT true);
CREATE TABLE official_resource(resource_id uuid PRIMARY KEY,agency_name varchar(100),title varchar(255),source_url text,checked_at timestamptz);
CREATE TABLE admin_area(area_code varchar(20) PRIMARY KEY,name varchar(100),level varchar(20),center_point geography(Point,4326),boundary geometry(MultiPolygon,4326),active boolean DEFAULT true);
CREATE TABLE data_source(source_id uuid PRIMARY KEY,name varchar(100),source_kind varchar(20),endpoint_key varchar(100) UNIQUE,active boolean DEFAULT true);
CREATE TABLE member_location_consent(member_id uuid PRIMARY KEY,consented boolean NOT NULL,changed_at timestamptz);
CREATE TABLE member_location(member_id uuid PRIMARY KEY,location_mode varchar(12),point geography(Point,4326) NOT NULL,updated_at timestamptz NOT NULL);
CREATE TABLE disaster(disaster_id uuid PRIMARY KEY,disaster_type_id uuid,status varchar(15),data_origin varchar(10));
CREATE TABLE disaster_area(disaster_area_id uuid PRIMARY KEY,disaster_id uuid,area_code varchar(20),area_role varchar(15),basis varchar(20));

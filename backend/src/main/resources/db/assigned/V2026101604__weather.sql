-- The shared-schema owners provide data_source and admin_area; no foreign tables are created here.
CREATE TABLE weather_observation (
 weather_id uuid PRIMARY KEY, source_id uuid NOT NULL REFERENCES data_source(source_id),
 area_code varchar(20) REFERENCES admin_area(area_code), temperature_c numeric(4,1),
 condition_code varchar(30) NOT NULL CHECK(condition_code IN ('SUNNY','CLOUDY','OVERCAST','RAIN','SNOW','SLEET','UNKNOWN')),
 observed_at timestamptz NOT NULL, collected_at timestamptz NOT NULL,
 grid_x smallint NOT NULL, grid_y smallint NOT NULL,
 condition_basis varchar(15) NOT NULL CHECK(condition_basis IN ('OBSERVATION','FORECAST','UNKNOWN')),
 sky_valid_at timestamptz, source_payload jsonb NOT NULL,
 UNIQUE(source_id,grid_x,grid_y,observed_at)
);

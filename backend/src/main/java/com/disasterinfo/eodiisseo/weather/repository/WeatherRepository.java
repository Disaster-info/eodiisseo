package com.disasterinfo.eodiisseo.weather.repository;

import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;

/** SQL and bind parameters for the weather feature. Transactions are owned by the service. */
@Repository @Profile("!test")
public class WeatherRepository {
    private final OwnedDatabase db;
    public WeatherRepository(OwnedDatabase db) { this.db=db; }

    public Map<String,Object> findInterestArea(UUID interestArea) {
        return db.one("select i.member_id,i.display_name,ST_Y(a.center_point::geometry) as latitude,ST_X(a.center_point::geometry) as longitude from interest_area i join admin_area a using(area_code) where i.interest_area_id=?",interestArea);
    }

    public Map<String,Object> findActiveWeatherSource(String sourceKey) {
        return db.one("select source_id from data_source where endpoint_key=? and source_kind='WEATHER' and active=true",sourceKey);
    }

    public Map<String,Object> lockGrid(String grid) {
        return db.one("select pg_advisory_xact_lock(hashtextextended(?,1))",grid);
    }

    public int saveObservation(UUID weather, UUID source, Object temperature, String condition, Instant observedAt, Instant collectedAt, int gridX, int gridY, String basis, Instant skyValidAt, String payload) {
        return db.update("insert into weather_observation(weather_id,source_id,area_code,temperature_c,condition_code,observed_at,collected_at,grid_x,grid_y,condition_basis,sky_valid_at,source_payload) values(?,?,null,?,?,?,?,?,?,?,?,?::jsonb) on conflict(source_id,grid_x,grid_y,observed_at) do update set temperature_c=excluded.temperature_c,condition_code=excluded.condition_code,collected_at=excluded.collected_at,condition_basis=excluded.condition_basis,sky_valid_at=excluded.sky_valid_at,source_payload=excluded.source_payload",weather,source,temperature,condition,observedAt,collectedAt,gridX,gridY,basis,skyValidAt,payload);
    }

    public Map<String,Object> findLatestObservation(UUID source, int gridX, int gridY) {
        return db.one("select * from weather_observation where source_id=? and grid_x=? and grid_y=? order by observed_at desc limit 1",source,gridX,gridY);
    }

    public boolean tableExists(String table) { return db.tableExists(table); }
}

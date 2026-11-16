package com.disasterinfo.eodiisseo.shelter.repository;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.common.response.GeoPoint;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only lookup; coordinates never become a member GPS update. */
@Repository @Profile("!test")
public class InterestAreaLocationRepository {
    private final JdbcTemplate jdbc;
    public InterestAreaLocationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public GeoPoint resolve(UUID member,UUID id){
        var row=owned(member,id);
        if(!Boolean.TRUE.equals(row.get("active"))||row.get("latitude")==null||row.get("longitude")==null)
            throw new ApiProblem(503,"AREA_LOCATION_UNAVAILABLE","관심지역의 공식 중심 좌표가 필요합니다.");
        return new GeoPoint(((Number)row.get("latitude")).doubleValue(),((Number)row.get("longitude")).doubleValue());
    }
    public void verifyOwned(UUID member,UUID id){owned(member,id);}
    private java.util.Map<String,Object> owned(UUID member,UUID id){
        if(member==null)throw ApiProblem.unauthorized();
        var rows=jdbc.queryForList("""
            select i.member_id,a.active,ST_Y(a.center_point::geometry) latitude,
              ST_X(a.center_point::geometry) longitude
            from interest_area i join admin_area a using(area_code) where i.interest_area_id=?
            """,id);
        if(rows.isEmpty())throw ApiProblem.missing("INTEREST_AREA_NOT_FOUND");
        var row=rows.getFirst();
        if(!member.equals(row.get("member_id")))throw ApiProblem.forbidden();
        return row;
    }
}

package com.disasterinfo.eodiisseo.area.service;

import com.disasterinfo.eodiisseo.area.dto.AdministrativeAreaInput;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.location.service.LocationService;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service @Profile("!test")
public class AdministrativeAreaImportService {
    private final OwnedDatabase db;private final ObjectMapper json;private final Clock clock;
    public AdministrativeAreaImportService(OwnedDatabase db,ObjectMapper json,Clock clock){this.db=db;this.json=json;this.clock=clock;}
    @Transactional public int importAreas(List<AdministrativeAreaInput> rows){
        if(rows==null||rows.isEmpty()||rows.size()>1000)throw ApiProblem.bad("한 번에 1~1000개 지역을 입력하세요.");
        db.rows("select pg_advisory_xact_lock(78161107)");
        Set<String> seen=new HashSet<>();
        for(var row:rows){
            if(!Set.of("SIDO","SIGUNGU","EUPMYEONDONG").contains(row.level())||!seen.add(row.regionCode()))throw ApiProblem.bad("행정구역 단위 또는 중복 코드를 확인하세요.");
            URI uri;try{uri=URI.create(row.sourceUrl());}catch(Exception e){throw ApiProblem.bad("출처 URL을 확인하세요.");}
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null)throw ApiProblem.bad("인증정보 없는 출처 URL이 필요합니다.");
            if(row.sourceUpdatedAt().isAfter(clock.instant())||row.baseYear()>java.time.Year.now(clock).getValue())throw ApiProblem.bad("자료 기준시각을 확인하세요.");
            if((row.latitude()==null)!=(row.longitude()==null))throw ApiProblem.bad("중심 좌표를 함께 입력하세요.");
            if(row.latitude()!=null)LocationService.coordinates(row.latitude(),row.longitude());
            String geometry=row.boundary()==null?null:json.writeValueAsString(row.boundary());
            if(geometry!=null){
                if(!Set.of("Polygon","MultiPolygon").contains(row.boundary().get("type")))throw ApiProblem.bad("WGS84 Polygon 또는 MultiPolygon만 지원합니다.");
                var check=db.one("select ST_IsValid(g) and not ST_IsEmpty(g) and ST_CoveredBy(g,ST_MakeEnvelope(-180,-90,180,90,4326)) as valid from (select ST_SetSRID(ST_GeomFromGeoJSON(?),4326) as g) x",geometry);
                if(!Boolean.TRUE.equals(check.get("valid")))throw ApiProblem.bad("유효한 WGS84 경계가 필요합니다.");
            }
            var previous=db.one("select boundary_source_updated_at from admin_area where area_code=?",row.regionCode());
            if(previous!=null&&previous.get("boundary_source_updated_at")!=null&&OwnedDatabase.instant(previous.get("boundary_source_updated_at")).isAfter(row.sourceUpdatedAt()))throw ApiProblem.conflict("ADMIN_AREA_OLDER_VERSION");
            db.update("insert into admin_area(area_code,name,level,active) values(?,?,?,?) on conflict(area_code) do nothing",row.regionCode(),row.name(),row.level(),row.active());
        }
        for(var row:rows){
            if(row.parentRegionCode()!=null&&db.count("select count(*) from admin_area where area_code=?",row.parentRegionCode())!=1)throw ApiProblem.bad("상위 행정구역을 먼저 등록하세요.");
            String geometry=row.boundary()==null?null:json.writeValueAsString(row.boundary());
            db.update("update admin_area set name=?,level=?,parent_area_code=?,active=?,center_point=case when ?::double precision is null then null else ST_SetSRID(ST_MakePoint(?::double precision,?::double precision),4326)::geography end,boundary=case when ?::text is null then null else ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?),4326)) end,boundary_source_url=?,boundary_source_updated_at=?,boundary_collected_at=?,boundary_source_provider=?,boundary_source_area_code=?,boundary_base_year=? where area_code=?",row.name(),row.level(),row.parentRegionCode(),row.active(),row.latitude(),row.longitude(),row.latitude(),geometry,geometry,row.sourceUrl(),row.sourceUpdatedAt(),clock.instant(),row.sourceProvider(),row.sourceAreaCode(),row.baseYear(),row.regionCode());
        }
        return rows.size();
    }
}

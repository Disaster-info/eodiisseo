package com.disasterinfo.eodiisseo.area.repository;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository @Profile("!test")
public class AreaRepository {
    private final OwnedDatabase db;
    public AreaRepository(OwnedDatabase db){this.db=db;}
    public void lock(UUID member){db.lockMember(member);}
    public Map<String,Object> find(UUID id){return db.one("select * from interest_area where interest_area_id=?",id);}
    public Map<String,Object> region(String code){return db.one("select * from admin_area where area_code=? and active and level='SIGUNGU'",code);}
    public long count(UUID member){return db.count("select count(*) from interest_area where member_id=?",member);}
    public boolean duplicate(UUID member,String code,UUID except){return db.count("select count(*) from interest_area where member_id=? and area_code=? and interest_area_id<>?",member,code,except)>0;}
    public void create(UUID id,UUID member,String code,String name,Instant now){db.update("insert into interest_area values(?,?,?,?,true,?)",id,member,code,name,now);}
    public void update(UUID id,String code,String name){db.update("update interest_area set area_code=?,display_name=? where interest_area_id=?",code,name,id);}
    public void delete(UUID id){db.update("delete from interest_area where interest_area_id=?",id);}
    public List<Map<String,Object>> list(UUID member){return db.rows("select interest_area_id as \"interestAreaId\",area_code as \"regionCode\",display_name as \"displayName\" from interest_area where member_id=? order by created_at,interest_area_id",member);}
    public Map<String,Object> center(UUID member,UUID id){return db.one("select i.member_id,a.name,ST_Y(a.center_point::geometry) as latitude,ST_X(a.center_point::geometry) as longitude from interest_area i join admin_area a using(area_code) where i.interest_area_id=? and i.member_id=? and a.active",id,member);}
    public Map<String,Object> at(double latitude,double longitude){return db.one("select name from admin_area where active and level='SIGUNGU' and boundary is not null and ST_Covers(boundary,ST_SetSRID(ST_MakePoint(?,?),4326)) order by area_code limit 1",longitude,latitude);}
}

package com.disasterinfo.eodiisseo.area.service;

import com.disasterinfo.eodiisseo.area.repository.AreaRepository;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.time.Clock;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.object;

@Service @Profile("!test")
public class AreaService {
    private final AreaRepository repository;private final Clock clock;
    public AreaService(AreaRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    @Transactional public Map<String,Object> create(UUID member,String code,String name){
        repository.lock(member);validate(code,name);
        if(repository.duplicate(member,code,new UUID(0,0)))throw ApiProblem.conflict("INTEREST_AREA_DUPLICATED");
        if(repository.count(member)>=5)throw ApiProblem.conflict("INTEREST_AREA_LIMIT_EXCEEDED");
        UUID id=UUID.randomUUID();repository.create(id,member,code,name.strip(),clock.instant());return response(id,code,name.strip());
    }
    @Transactional(readOnly=true) public Map<String,Object> list(UUID member){return Map.of("interestAreas",repository.list(member));}
    @Transactional public Map<String,Object> update(UUID member,UUID id,String code,String name){
        repository.lock(member);var old=owned(member,id);
        if(code==null&&name==null)throw ApiProblem.bad("변경할 항목이 필요합니다.");
        code=code==null?(String)old.get("area_code"):code;name=name==null?(String)old.get("display_name"):name;
        validate(code,name);
        if(repository.duplicate(member,code,id))throw ApiProblem.conflict("INTEREST_AREA_DUPLICATED");
        repository.update(id,code,name.strip());return response(id,code,name.strip());
    }
    @Transactional public void delete(UUID member,UUID id){repository.lock(member);owned(member,id);repository.delete(id);}
    public Map<String,Object> owned(UUID member,UUID id){
        var row=repository.find(id);if(row==null)throw ApiProblem.missing("INTEREST_AREA_NOT_FOUND");
        if(!member.equals(row.get("member_id")))throw ApiProblem.forbidden();return row;
    }
    private void validate(String code,String name){
        if(code==null||code.isBlank()||code.length()>20||repository.region(code)==null)throw ApiProblem.bad("유효한 시·군·구 코드를 입력하세요.");
        if(name==null||name.isBlank()||name.strip().length()>50)throw ApiProblem.bad("표시명을 확인하세요.");
    }
    private Map<String,Object> response(UUID id,String code,String name){return object("interestAreaId",id,"regionCode",code,"displayName",name);}
}

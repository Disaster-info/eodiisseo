package com.disasterinfo.eodiisseo.checklist.service;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;

import com.disasterinfo.eodiisseo.checklist.repository.ChecklistRepository;
import java.math.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.*;

@Service @Profile("!test")
public class ChecklistService {
    private final ChecklistRepository repository;private final Clock clock;
    public ChecklistService(ChecklistRepository repository,Clock clock) { this.repository=repository;this.clock=clock; }
    @Transactional(readOnly=true) public Map<String,Object> get(UUID member,String type) {
        validType(type);requireReferences();
        var template=repository.findActiveTemplate(type);
        if(template==null) throw ApiProblem.missing("CHECKLIST_TEMPLATE_NOT_FOUND");
        var items=items(member,(UUID)template.get("template_id"));
        var stats=statistics(items);stats.put("checklistId",template.get("template_id"));stats.put("disasterType",type);
        stats.put("source",object("agency",template.get("agency_name"),"reference",template.get("reference"),"url",template.get("source_url")));
        stats.put("items",items);return stats;
    }
    @Transactional public Map<String,Object> complete(UUID member,UUID template,UUID item,boolean completed) {
        repository.lockMember(member);
        var found=repository.findActiveItemForShare(item,template);
        if(found==null) throw ApiProblem.missing("CHECKLIST_ITEM_NOT_FOUND");
        if(completed) repository.markCompleted(member,item,clock.instant());
        else repository.clearCompleted(member,item);
        var stats=statistics(items(member,template));stats.put("itemId",item);stats.put("completed",completed);return stats;
    }
    private List<Map<String,Object>> items(UUID member,UUID template) {
        return repository.listMemberItems(member,template);
    }
    private Map<String,Object> statistics(List<Map<String,Object>> items) {
        long completed=items.stream().filter(m->Boolean.TRUE.equals(m.get("completed"))).count();
        return object("totalCount",items.size(),"completedCount",completed,"completionRate",completionRate(items.size(),completed));
    }
    public static BigDecimal completionRate(long total,long completed) {
        if(total==0) return BigDecimal.ZERO.setScale(1);
        return BigDecimal.valueOf(completed).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total),1,RoundingMode.HALF_UP);
    }
    private void requireReferences() {
        if(!repository.tableExists("disaster_type") || !repository.tableExists("official_resource")) throw new ApiProblem(503,"OFFICIAL_RESOURCE_NOT_READY","공식 자료 담당 스키마 연결이 필요합니다.");
    }
    private void validType(String type) { if(!Set.of("EARTHQUAKE","HEAVY_RAIN","WILDFIRE").contains(type)) throw ApiProblem.bad("지원하지 않는 재난 유형입니다."); }
}

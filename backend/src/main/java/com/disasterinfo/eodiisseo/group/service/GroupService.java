package com.disasterinfo.eodiisseo.group.service;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;

import com.disasterinfo.eodiisseo.group.repository.GroupRepository;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.*;

@Service @Profile("!test")
public class GroupService {
    private final GroupRepository repository;private final Clock clock;private final long staleSeconds;
    private final SecureRandom random=new SecureRandom();
    public GroupService(GroupRepository repository,Clock clock,@Value("${LOCATION_STALE_AFTER_SECONDS:300}") long staleSeconds) {
        this.repository=repository;this.clock=clock;this.staleSeconds=staleSeconds;
        if(staleSeconds<=0) throw new IllegalStateException("LOCATION_STALE_AFTER_SECONDS must be positive");
    }
    @Transactional public Map<String,Object> create(UUID member,String name,String category) {
        name=validName(name);category=validCategory(category);repository.lockMembershipChanges();repository.lockMember(member);checkGroupLimit(member);
        var id=UUID.randomUUID();String code=null;
        for(int i=0;i<10;i++) {
            var candidate=inviteCode();
            if(repository.createGroup(id,name,category,member,candidate,clock.instant())==1) {code=candidate;break;}
        }
        if(code==null) throw ApiProblem.conflict("INVITE_CODE_UNAVAILABLE");
        repository.addAdministrator(id,member,clock.instant());
        return object("groupId",id,"name",name,"category",category,"role","ADMIN","inviteCode",code);
    }
    public Map<String,Object> list(UUID member) {
        return object("groups",repository.listMemberGroups(member));
    }
    @Transactional(readOnly=true) public Map<String,Object> detail(UUID member,UUID group) {
        var mine=membership(member,group);var g=repository.findGroup(group);
        var members=repository.listActiveMembers(group);
        var admin=members.stream().filter(m->"ADMIN".equals(m.get("role"))).findFirst().orElseThrow(()->new IllegalStateException("Missing group administrator"));
        return object("groupId",group,"name",g.get("name"),"category",g.get("category"),"inviteCode",g.get("invite_code"),
            "admin",object("memberId",admin.get("memberId"),"name",admin.get("name")),"members",members,"myRole",mine.get("role"));
    }
    @Transactional public Map<String,Object> join(UUID member,String code) {
        if(code==null || !code.strip().matches("[a-zA-Z0-9]{6,32}")) throw ApiProblem.bad("초대 코드를 확인하세요.");
        repository.lockMembershipChanges();repository.lockMember(member);
        var g=repository.findInviteForUpdate(code.strip().toUpperCase(Locale.ROOT));
        if(g==null) throw ApiProblem.missing("GROUP_NOT_FOUND");var group=(UUID)g.get("group_id");
        if(repository.countMembership(group,member)>0) throw ApiProblem.conflict("ALREADY_GROUP_MEMBER");
        checkGroupLimit(member);
        if(repository.countGroupMembers(group)>=10) throw ApiProblem.conflict("GROUP_FULL");
        repository.addMember(group,member,clock.instant());
        return object("groupId",group,"name",g.get("name"),"category",g.get("category"),"role","MEMBER");
    }
    @Transactional public void exclude(UUID actor,UUID group,UUID target) {
        mutate(actor,group);admin(actor,group);if(actor.equals(target)) throw ApiProblem.conflict("CANNOT_EXCLUDE_ADMIN");
        membership(target,group);repository.removeMember(group,target);
    }
    @Transactional public Map<String,Object> transfer(UUID actor,UUID group,UUID target) {
        mutate(actor,group);admin(actor,group);
        if(actor.equals(target)) throw ApiProblem.conflict("ALREADY_GROUP_ADMIN");membership(target,group);
        repository.demoteAdministrator(group,actor);
        repository.promoteAdministrator(group,target);
        return object("groupId",group,"newAdminMemberId",target,"currentUserRole","MEMBER");
    }
    @Transactional public void leave(UUID actor,UUID group) {
        mutate(actor,group);
        if("ADMIN".equals(membership(actor,group).get("role"))) throw ApiProblem.conflict("GROUP_ADMIN_ACTION_REQUIRED");
        repository.removeMember(group,actor);
    }
    @Transactional public void delete(UUID actor,UUID group) {
        mutate(actor,group);admin(actor,group);
        if(repository.countGroupMembers(group)!=1) throw ApiProblem.conflict("GROUP_NOT_EMPTY");
        repository.removeAllMembers(group);repository.deleteGroup(group);
    }
    @Transactional public Map<String,Object> edit(UUID actor,UUID group,String name,String category) {
        if(name==null && category==null) throw ApiProblem.bad("변경할 항목이 필요합니다.");
        mutate(actor,group);admin(actor,group);var old=repository.findGroup(group);
        name=name==null?(String)old.get("name"):validName(name);category=category==null?(String)old.get("category"):validCategory(category);
        repository.updateGroup(name,category,group);
        return object("groupId",group,"name",name,"category",category);
    }
    @Transactional public Map<String,Object> sharing(UUID actor,UUID group,boolean enabled) {
        mutate(actor,group);membership(actor,group);
        if(enabled && (!repository.tableExists("member_location_consent") || repository.countLocationConsent(actor)!=1)) throw ApiProblem.forbidden();
        repository.updateLocationSharing(enabled,group,actor);
        return object("groupId",group,"enabled",enabled);
    }
    @Transactional(readOnly=true) public Map<String,Object> locations(UUID actor,UUID group) {
        membership(actor,group);requireLocationSchema();var now=clock.instant();
        var members=repository.listMemberLocations(group);
        var result=new ArrayList<Map<String,Object>>();
        for(var m:members) {
            boolean shared=Boolean.TRUE.equals(m.get("location_sharing_enabled")) && Boolean.TRUE.equals(m.get("consented"));
            var updated=shared?instant(m.get("updated_at")):null;
            var state=locationState(shared,updated,now,staleSeconds);
            result.add(object("memberId",m.get("member_id"),"name",m.get("display_name"),"locationStatus",state,
                "latitude",updated==null?null:m.get("latitude"),"longitude",updated==null?null:m.get("longitude"),"updatedAt",updated));
        }
        return object("members",result);
    }
    @Transactional(readOnly=true) @SuppressWarnings("unchecked") public Map<String,Object> impacts(UUID actor,UUID group) {
        var members=(List<Map<String,Object>>)locations(actor,group).get("members");var results=new ArrayList<Map<String,Object>>();var now=clock.instant();
        if(!repository.tableExists("disaster") || !repository.tableExists("disaster_area") || !repository.tableExists("admin_area")) throw new ApiProblem(503,"GIS_DEPENDENCY_NOT_READY","공통 GIS 스키마 연결이 필요합니다.");
        var disasters=repository.listActiveOfficialDisasters();
        for(var m:members) {
            var affected=new ArrayList<Map<String,Object>>();boolean unknown=false;
            if("AVAILABLE".equals(m.get("locationStatus"))) for(var disaster:disasters) {
                var areas=repository.findImpactCoverage(m.get("longitude"),m.get("latitude"),(UUID)disaster.get("disaster_id"));
                if(areas.isEmpty() || areas.stream().anyMatch(a->!Boolean.TRUE.equals(a.get("known")))) unknown=true;
                var included=areas.stream().filter(a->Boolean.TRUE.equals(a.get("included"))).findFirst();
                if(included.isPresent()) affected.add(object("disasterId",disaster.get("disaster_id"),"basis",included.get().get("basis")));
            }
            String state=!"AVAILABLE".equals(m.get("locationStatus"))?"LOCATION_UNAVAILABLE":!affected.isEmpty()?"INSIDE_AFFECTED_AREA":unknown?"AFFECTED_AREA_UNKNOWN":"OUTSIDE_AFFECTED_AREA";
            results.add(object("memberId",m.get("memberId"),"name",m.get("name"),"status",state,"locationStatus",m.get("locationStatus"),"locationUpdatedAt",m.get("updatedAt"),"evaluatedAt",now,"affectedDisasters",affected));
        }
        return object("members",results);
    }
    private void mutate(UUID actor,UUID group) { repository.lockMembershipChanges();repository.lockMember(actor);if(repository.lockGroup(group)==null) throw ApiProblem.missing("GROUP_NOT_FOUND"); }
    private Map<String,Object> membership(UUID member,UUID group) {
        if(repository.countGroup(group)==0) throw ApiProblem.missing("GROUP_NOT_FOUND");
        var m=repository.findActiveMembership(group,member);
        if(m==null) throw ApiProblem.forbidden();return m;
    }
    private void admin(UUID member,UUID group) { if(!"ADMIN".equals(membership(member,group).get("role"))) throw ApiProblem.forbidden(); }
    private void checkGroupLimit(UUID member) { if(repository.countMemberGroups(member)>=3) throw ApiProblem.conflict("GROUP_LIMIT_EXCEEDED"); }
    private void requireLocationSchema() {
        if(!repository.tableExists("member_location") || !repository.tableExists("member_location_consent")) throw new ApiProblem(503,"LOCATION_DEPENDENCY_NOT_READY","위치 담당 스키마 연결이 필요합니다.");
    }
    static String validName(String name) { if(name==null || name.strip().isBlank() || name.strip().length()>80) throw ApiProblem.bad("그룹 이름을 확인하세요.");return name.strip(); }
    static String validCategory(String category) { if(category==null) return "OTHER";if(!Set.of("FAMILY","FRIEND","OTHER").contains(category)) throw ApiProblem.bad("그룹 분류를 확인하세요.");return category; }
    public static String locationState(boolean sharing,Instant updated,Instant now,long staleSeconds) {
        return !sharing?"NOT_SHARED":updated==null?"NO_LOCATION":!updated.plusSeconds(staleSeconds).isAfter(now)?"STALE":"AVAILABLE";
    }
    private String inviteCode() { var chars="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";var b=new StringBuilder();for(int i=0;i<6;i++) b.append(chars.charAt(random.nextInt(chars.length())));return b.toString(); }
}

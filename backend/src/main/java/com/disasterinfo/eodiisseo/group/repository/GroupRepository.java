package com.disasterinfo.eodiisseo.group.repository;

import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;

/** SQL and bind parameters for the group feature. Transactions are owned by the service. */
@Repository @Profile("!test")
public class GroupRepository {
    private final OwnedDatabase db;
    public GroupRepository(OwnedDatabase db) { this.db=db; }

    public int createGroup(UUID group, String name, String category, UUID creator, String inviteCode, Instant now) {
        return db.update("insert into safety_group(group_id,name,category,created_by,invite_code,created_at) values(?,?,?,?,?,?) on conflict(invite_code) do nothing",group,name,category,creator,inviteCode,now);
    }

    public int addAdministrator(UUID group, UUID member, Instant now) {
        return db.update("insert into group_member(group_id,member_id,role,location_sharing_enabled,joined_at) values(?,?,'ADMIN',false,?)",group,member,now);
    }

    public List<Map<String,Object>> listMemberGroups(UUID member) {
        return db.rows("select g.group_id as \"groupId\",g.name,g.category,m.role from safety_group g join group_member m using(group_id) where m.member_id=? order by g.created_at,g.group_id",member);
    }

    public List<Map<String,Object>> listActiveMembers(UUID group) {
        return db.rows("select m.member_id as \"memberId\",m.display_name as name,gm.role from group_member gm join member m using(member_id) where gm.group_id=? and m.status='ACTIVE' order by gm.joined_at,m.member_id",group);
    }

    public Map<String,Object> findInviteForUpdate(String inviteCode) {
        return db.one("select * from safety_group where invite_code=? for update",inviteCode);
    }

    public long countMembership(UUID group, UUID member) {
        return db.count("select count(*) from group_member where group_id=? and member_id=?",group,member);
    }

    public int addMember(UUID group, UUID member, Instant now) {
        return db.update("insert into group_member(group_id,member_id,role,location_sharing_enabled,joined_at) values(?,?,'MEMBER',false,?)",group,member,now);
    }

    public int demoteAdministrator(UUID group, UUID member) {
        return db.update("update group_member set role='MEMBER' where group_id=? and member_id=?",group,member);
    }

    public int promoteAdministrator(UUID group, UUID member) {
        return db.update("update group_member set role='ADMIN' where group_id=? and member_id=?",group,member);
    }

    public int removeMember(UUID group, UUID member) {
        return db.update("delete from group_member where group_id=? and member_id=?",group,member);
    }

    public long countGroupMembers(UUID group) {
        return db.count("select count(*) from group_member where group_id=?",group);
    }

    public int removeAllMembers(UUID group) {
        return db.update("delete from group_member where group_id=?",group);
    }

    public int deleteGroup(UUID group) {
        return db.update("delete from safety_group where group_id=?",group);
    }

    public Map<String,Object> findGroup(UUID group) {
        return db.one("select * from safety_group where group_id=?",group);
    }

    public int updateGroup(String name, String category, UUID group) {
        return db.update("update safety_group set name=?,category=? where group_id=?",name,category,group);
    }

    public long countLocationConsent(UUID member) {
        return db.count("select count(*) from member_location_consent where member_id=? and consented=true",member);
    }

    public int updateLocationSharing(boolean enabled, UUID group, UUID member) {
        return db.update("update group_member set location_sharing_enabled=? where group_id=? and member_id=?",enabled,group,member);
    }

    public List<Map<String,Object>> listMemberLocations(UUID group) {
        return db.rows("select m.member_id,m.display_name,gm.location_sharing_enabled,c.consented,ST_Y(l.point::geometry) as latitude,ST_X(l.point::geometry) as longitude,l.updated_at from group_member gm join member m using(member_id) left join member_location_consent c using(member_id) left join member_location l using(member_id) where gm.group_id=? and m.status='ACTIVE' order by gm.joined_at,m.member_id",group);
    }

    public List<Map<String,Object>> listActiveOfficialDisasters() {
        return db.rows("select disaster_id from disaster where status in ('OCCURRED','ONGOING') and data_origin='OFFICIAL'");
    }

    public List<Map<String,Object>> findImpactCoverage(Object longitude, Object latitude, UUID disaster) {
        return db.rows("select da.basis,a.boundary is not null as known,case when a.boundary is null then null else ST_Covers(a.boundary,ST_SetSRID(ST_MakePoint(?,?),4326)) end as included from disaster_area da left join admin_area a using(area_code) where da.disaster_id=? and da.area_role='IMPACT' and da.basis=case when exists(select 1 from disaster_area official where official.disaster_id=da.disaster_id and official.area_role='IMPACT' and official.basis='OFFICIAL') then 'OFFICIAL' else 'SOURCE_ADDRESS' end",longitude,latitude,disaster);
    }

    public Map<String,Object> lockGroup(UUID group) {
        return db.one("select group_id from safety_group where group_id=? for update",group);
    }

    public long countGroup(UUID group) {
        return db.count("select count(*) from safety_group where group_id=?",group);
    }

    public Map<String,Object> findActiveMembership(UUID group, UUID member) {
        return db.one("select gm.* from group_member gm join member m using(member_id) where gm.group_id=? and gm.member_id=? and m.status='ACTIVE'",group,member);
    }

    public long countMemberGroups(UUID member) {
        return db.count("select count(*) from group_member where member_id=?",member);
    }

    public boolean tableExists(String table) { return db.tableExists(table); }
    public void lockMember(UUID member) { db.lockMember(member); }
    public void lockMembershipChanges() { db.lockMembershipChanges(); }
}

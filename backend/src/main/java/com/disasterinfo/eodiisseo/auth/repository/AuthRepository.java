package com.disasterinfo.eodiisseo.auth.repository;

import java.util.*;
import java.time.Instant;
import com.disasterinfo.eodiisseo.auth.entity.AuthSession;
import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.instant;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

/** SQL and bind parameters for the auth feature. Transactions are owned by the service. */
@Repository @Profile("!test")
public class AuthRepository {
    private final OwnedDatabase db;
    public AuthRepository(OwnedDatabase db) { this.db=db; }

    public int createMember(UUID member, String name, Instant now) {
        return db.update("insert into member(member_id,display_name,status,created_at) values(?,?,'ACTIVE',?)",member,name,now);
    }

    public int grantUserRole(UUID member, Instant now) {
        return db.update("insert into member_role(member_id,role,granted_at) values(?,'USER',?)",member,now);
    }

    public Map<String,Object> findIdentityForUpdate(String provider, String subject) {
        return db.one("select m.* from social_account a join member m using(member_id) where a.provider=? and a.provider_user_id=? for update of m",provider,subject);
    }

    public AuthSession findRefreshSessionForUpdate(String currentHash, String previousHash) {
        var row=db.one("select s.* from auth_session s where s.refresh_token_hash=? or exists(select 1 from auth_refresh_history h where h.session_id=s.session_id and h.token_hash=?) for update",currentHash,previousHash);
        return row==null ? null : new AuthSession((UUID)row.get("session_id"),(UUID)row.get("member_id"),
            (String)row.get("refresh_token_hash"),instant(row.get("expires_at")),instant(row.get("revoked_at")));
    }

    public long countActiveMember(UUID member) {
        return db.count("select count(*) from member where member_id=? and status='ACTIVE'",member);
    }

    public int revokeSession(Instant now, UUID session) {
        return db.update("update auth_session set revoked_at=coalesce(revoked_at,?) where session_id=?",now,session);
    }

    public int recordUsedRefreshToken(String hash, UUID session, Instant usedAt, Instant expiresAt) {
        return db.update("insert into auth_refresh_history(token_hash,session_id,used_at,expires_at) values(?,?,?,?)",hash,session,usedAt,expiresAt);
    }

    public int rotateRefreshToken(String hash, UUID session) {
        return db.update("update auth_session set refresh_token_hash=? where session_id=?",hash,session);
    }

    public Map<String,Object> findActiveMember(UUID member) {
        return db.one("select display_name from member where member_id=? and status='ACTIVE'",member);
    }

    public List<Map<String,Object>> listLinkedProviders(UUID member) {
        return db.rows("select provider from social_account where member_id=? order by provider",member);
    }

    public List<Map<String,Object>> listRoles(UUID member) {
        return db.rows("select role from member_role where member_id=? order by role",member);
    }

    public Map<String,Object> lockSocialIdentity(String identity) {
        return db.one("select pg_advisory_xact_lock(hashtextextended(?,0))",identity);
    }

    public Map<String,Object> findIdentityOwner(String provider, String subject) {
        return db.one("select member_id from social_account where provider=? and provider_user_id=?",provider,subject);
    }

    public int linkSocialAccount(UUID account, UUID member, String provider, String subject, Instant now) {
        return db.update("insert into social_account(social_account_id,member_id,provider,provider_user_id,linked_at) values(?,?,?,?,?)",account,member,provider,subject,now);
    }

    public int logoutSession(Instant now, UUID session, UUID member) {
        return db.update("update auth_session set revoked_at=? where session_id=? and member_id=?",now,session,member);
    }

    public long countAdministratedGroups(UUID member) {
        return db.count("select count(*) from group_member where member_id=? and role='ADMIN'",member);
    }

    public int markWithdrawalPending(Instant now, UUID member) {
        return db.update("update member set status='WITHDRAWAL_PENDING',withdrawn_at=? where member_id=?",now,member);
    }

    public int revokeMemberSessions(Instant now, UUID member) {
        return db.update("update auth_session set revoked_at=? where member_id=? and revoked_at is null",now,member);
    }

    public int removeGroupMemberships(UUID member) {
        return db.update("delete from group_member where member_id=?",member);
    }

    public int removeOpsRole(UUID member) {
        return db.update("delete from member_role where member_id=? and role='OPS'",member);
    }

    public int deleteAnswerResources(UUID member) {
        return db.update("delete from ai_answer_resource where answer_id in(select answer_id from ai_answer where member_id=?)",member);
    }

    public int deidentifyDeliveries(UUID member) {
        return db.update("update notification_delivery set device_token_id=null,provider_message_id=null where event_id in(select event_id from notification_event where member_id=?)",member);
    }

    public int deidentifyEvents(Instant now, UUID member) {
        return db.update("update notification_event set member_id=null,deidentified_at=? where member_id=?",now,member);
    }

    public int restoreMember(UUID member) {
        return db.update("update member set status='ACTIVE',withdrawn_at=null where member_id=?",member);
    }

    public int deleteRefreshHistory(UUID member) {
        return db.update("delete from auth_refresh_history where session_id in(select session_id from auth_session where member_id=?)",member);
    }

    public int deleteSessions(UUID member) {
        return db.update("delete from auth_session where member_id=?",member);
    }

    public int deleteSocialAccounts(UUID member) {
        return db.update("delete from social_account where member_id=?",member);
    }

    public int deleteRoles(UUID member) {
        return db.update("delete from member_role where member_id=?",member);
    }

    public int clearGroupCreator(UUID member) {
        return db.update("update safety_group set created_by=null where created_by=?",member);
    }

    public int deleteMember(UUID member) {
        return db.update("delete from member where member_id=?",member);
    }

    public List<Map<String,Object>> listExpiredWithdrawals(Instant cutoff) {
        return db.rows("select member_id from member where status='WITHDRAWAL_PENDING' and withdrawn_at<=? order by withdrawn_at limit 100",cutoff);
    }

    public Map<String,Object> findWithdrawalForUpdate(UUID member) {
        return db.one("select status,withdrawn_at from member where member_id=? for update",member);
    }

    public int deleteOptionalMemberData(String table, UUID member) {
        if(!Set.of("member_location","member_location_consent","ai_answer","device_token","interest_area","member_check_item","notification_setting").contains(table))
            throw new IllegalArgumentException("Unsupported member data table");
        return db.update("delete from "+table+" where member_id=?",member);
    }

    public int createSession(UUID session, UUID member, String hash, Instant now, Instant expiresAt) {
        return db.update("insert into auth_session(session_id,member_id,refresh_token_hash,created_at,expires_at) values(?,?,?,?,?)",session,member,hash,now,expiresAt);
    }

    public Map<String,Object> findUsableSession(UUID session, UUID member) {
        return db.one("select s.session_id from auth_session s join member m using(member_id) where s.session_id=? and s.member_id=? and s.revoked_at is null and s.expires_at>now() and m.status='ACTIVE'",session,member);
    }

    public Map<String,Object> lockUsableSession(UUID session, UUID member) {
        return db.one("select s.session_id from auth_session s join member m using(member_id) where s.session_id=? and s.member_id=? and s.revoked_at is null and s.expires_at>now() and m.status='ACTIVE' for update of s",session,member);
    }

    public List<Map<String,Object>> listCurrentRoles(UUID member) {
        return db.rows("select role from member_role where member_id=?",member);
    }

    public boolean tableExists(String table) { return db.tableExists(table); }
    public void lockMember(UUID member) { db.lockMember(member); }
    public void lockMembershipChanges() { db.lockMembershipChanges(); }
}

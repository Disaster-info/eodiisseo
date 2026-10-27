package com.disasterinfo.eodiisseo.auth.service;
import com.disasterinfo.eodiisseo.auth.dto.AuthDto.Login;
import com.disasterinfo.eodiisseo.auth.client.SocialIdentityVerifier;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;

import com.disasterinfo.eodiisseo.auth.repository.AuthRepository;
import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.*;

@Service @Profile("!test")
public class AuthService {
    private final AuthRepository repository;
    private final TokenService tokens;
    private final SocialIdentityVerifier social;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final long sessionSeconds;
    private final SecureRandom random=new SecureRandom();
    public AuthService(AuthRepository repository,TokenService tokens,SocialIdentityVerifier social,Clock clock,
        PlatformTransactionManager manager,@Value("${AUTH_SESSION_TTL_SECONDS:1209600}") long sessionSeconds) {
        this.repository=repository;this.tokens=tokens;this.social=social;this.clock=clock;
        this.transaction=new TransactionTemplate(manager);this.sessionSeconds=sessionSeconds;
        if(sessionSeconds<=0) throw new IllegalStateException("AUTH_SESSION_TTL_SECONDS must be positive");
    }
    public Login login(String provider,String credential) {
        var identity=social.verify(provider,credential);
        return transaction.execute(status->{
            // Same provider identity serializes first signup without merging unrelated providers.
            repository.lockSocialIdentity(identity.provider()+":"+identity.subject());
            var m=findIdentity(identity);boolean created=false;
            if(m!=null && "WITHDRAWAL_PENDING".equals(m.get("status"))) {
                var until=instant(m.get("withdrawn_at")).plus(Duration.ofDays(7));
                if(clock.instant().isBefore(until)) return new Login(object("memberId",m.get("member_id"),"accountStatus","WITHDRAWAL_PENDING","recoverableUntil",until),false);
                purge((UUID)m.get("member_id"));m=null;
            }
            if(m==null) {
                var id=UUID.randomUUID();var now=clock.instant();
                repository.createMember(id,identity.name(),now);
                repository.linkSocialAccount(UUID.randomUUID(),id,provider,identity.subject(),now);
                repository.grantUserRole(id,now);
                m=object("member_id",id,"display_name",identity.name());created=true;
            }
            var body=newSession((UUID)m.get("member_id"));body.put("memberId",m.get("member_id"));body.put("name",m.get("display_name"));
            body.put("provider",provider);body.put("accountStatus","ACTIVE");return new Login(body,created);
        });
    }
    private Map<String,Object> findIdentity(SocialIdentityVerifier.Identity i) {
        return repository.findIdentityForUpdate(i.provider(),i.subject());
    }
    public Map<String,Object> refresh(String refresh) {
        if(refresh==null || refresh.isBlank() || refresh.length()>512) throw ApiProblem.bad("갱신 토큰을 확인하세요.");
        var hash=hash(refresh);
        // Reuse revocation must COMMIT even when the HTTP result is 401.
        var result=transaction.execute(tx->{
            var s=repository.findRefreshSessionForUpdate(hash,hash);
            if(s==null) return null;
            var now=clock.instant();var id=s.sessionId();
            var active=repository.countActiveMember(s.memberId())==1;
            if(!active || !s.accepts(hash,now)) {
                repository.revokeSession(now,id);return null;
            }
            var next=randomToken();var expiry=s.expiresAt();
            repository.recordUsedRefreshToken(hash,id,now,expiry);
            repository.rotateRefreshToken(hash(next),id);
            return tokenResponse(s.memberId(),id,next,expiry);
        });
        if(result==null) throw ApiProblem.unauthorized();return result;
    }
    public Map<String,Object> me(MemberPrincipal p) {
        var m=repository.findActiveMember(p.memberId());
        if(m==null) throw ApiProblem.unauthorized();
        return object("memberId",p.memberId(),"name",m.get("display_name"),"linkedProviders",repository.listLinkedProviders(p.memberId()).stream().map(v->v.get("provider")).toList(),
            "roles",repository.listRoles(p.memberId()).stream().map(v->v.get("role")).toList());
    }
    public Map<String,Object> link(MemberPrincipal p,String provider,String credential) {
        var identity=social.verify(provider,credential);
        return transaction.execute(tx->{
            repository.lockSocialIdentity(provider+":"+identity.subject());repository.lockMember(p.memberId());
            var other=repository.findIdentityOwner(provider,identity.subject());
            if(other!=null && !p.memberId().equals(other.get("member_id"))) throw ApiProblem.conflict("SOCIAL_ACCOUNT_ALREADY_LINKED");
            if(other==null) repository.linkSocialAccount(UUID.randomUUID(),p.memberId(),provider,identity.subject(),clock.instant());
            return object("provider",provider,"linked",true);
        });
    }
    public void logout(MemberPrincipal p) { repository.logoutSession(clock.instant(),p.sessionId(),p.memberId()); }
    public void withdraw(MemberPrincipal p) {
        transaction.executeWithoutResult(tx->{
            repository.lockMembershipChanges();repository.lockMember(p.memberId());
            if(repository.countAdministratedGroups(p.memberId())>0) throw ApiProblem.conflict("GROUP_ADMIN_ACTION_REQUIRED");
            repository.markWithdrawalPending(clock.instant(),p.memberId());
            repository.revokeMemberSessions(clock.instant(),p.memberId());
            repository.removeGroupMemberships(p.memberId());
            repository.removeOpsRole(p.memberId());
            discardOptional("member_location",p.memberId());discardOptional("member_location_consent",p.memberId());
            if(repository.tableExists("ai_answer_resource") && repository.tableExists("ai_answer")) repository.deleteAnswerResources(p.memberId());
            discardOptional("ai_answer",p.memberId());
            if(repository.tableExists("notification_event")) {
                if(repository.tableExists("notification_delivery")) repository.deidentifyDeliveries(p.memberId());
                repository.deidentifyEvents(clock.instant(),p.memberId());
            }
            discardOptional("device_token",p.memberId());
        });
    }
    public Map<String,Object> restore(String provider,String credential) {
        var identity=social.verify(provider,credential);
        return transaction.execute(tx->{
            var m=findIdentity(identity);if(m==null) throw ApiProblem.missing("MEMBER_NOT_FOUND");
            if(!"WITHDRAWAL_PENDING".equals(m.get("status"))) throw ApiProblem.conflict("MEMBER_NOT_WITHDRAWN");
            if(!clock.instant().isBefore(instant(m.get("withdrawn_at")).plus(Duration.ofDays(7)))) throw new ApiProblem(410,"RECOVERY_EXPIRED","복구기간이 지났습니다.");
            var id=(UUID)m.get("member_id");repository.restoreMember(id);
            var body=newSession(id);body.put("memberId",id);body.put("accountStatus","ACTIVE");body.put("groupsRestored",false);return body;
        });
    }
    private void purge(UUID member) {
        // Only expired, already-withdrawn members reach here; source-owned folders remain untouched.
        for(var table:List.of("interest_area","member_check_item","notification_setting","auth_refresh_history")) {
            if(table.equals("auth_refresh_history")) repository.deleteRefreshHistory(member);
            else discardOptional(table,member);
        }
        repository.deleteSessions(member);repository.deleteSocialAccounts(member);
        repository.deleteRoles(member);
        if(repository.tableExists("safety_group")) repository.clearGroupCreator(member);
        repository.deleteMember(member);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${MEMBER_RETENTION_SWEEP_MS:3600000}")
    public void purgeExpiredMembers() {
        var cutoff=clock.instant().minus(Duration.ofDays(7));
        var members=repository.listExpiredWithdrawals(cutoff);
        for(var member:members) transaction.executeWithoutResult(tx->{
            var locked=repository.findWithdrawalForUpdate((UUID)member.get("member_id"));
            if(locked!=null && "WITHDRAWAL_PENDING".equals(locked.get("status")) && !instant(locked.get("withdrawn_at")).isAfter(cutoff)) purge((UUID)member.get("member_id"));
        });
    }
    private void discardOptional(String table,UUID member) { if(repository.tableExists(table)) repository.deleteOptionalMemberData(table, member); }
    private Map<String,Object> newSession(UUID member) {
        var id=UUID.randomUUID();var refresh=randomToken();var now=clock.instant();var expiry=now.plusSeconds(sessionSeconds);
        repository.createSession(id,member,hash(refresh),now,expiry);
        return tokenResponse(member,id,refresh,expiry);
    }
    private Map<String,Object> tokenResponse(UUID member,UUID session,String refresh,Instant expiry) {
        return object("accessToken",tokens.issue(member,session,expiry),"refreshToken",refresh,"tokenType","Bearer",
            "expiresIn",Math.min(tokens.seconds(),Duration.between(clock.instant(),expiry).getSeconds()),"refreshExpiresAt",expiry);
    }
    private String randomToken() { var bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

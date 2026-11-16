package com.disasterinfo.eodiisseo.ai.resource.adapter;
import com.disasterinfo.eodiisseo.ai.resource.port.ResourceApprovalPort;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.service.*;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("!test")
public class DatabaseResourceApproval implements ResourceApprovalPort {
    private final OwnedDatabase db; private final Clock clock; private final SessionAccessService sessions; private final AuthService auth;
    private static final Pattern INJECTION=Pattern.compile("(?iu)ignore.{0,30}(previous|instruction)|system.{0,10}prompt|<.*(system|assistant)|javascript:");
    public DatabaseResourceApproval(OwnedDatabase db,Clock clock,SessionAccessService sessions,AuthService auth){this.db=db;this.clock=clock;this.sessions=sessions;this.auth=auth;}
    private OfficialResourceDocument document(UUID id) {
        var row=db.one("select * from official_resource where resource_id=?",id);
        return row==null?null:new OfficialResourceDocument(id,(String)row.get("agency_name"),(String)row.get("title"),(String)row.get("source_url"),
            (String)row.get("version_label"),(String)row.get("content_text"),OwnedDatabase.instant(row.get("checked_at")));
    }
    public Optional<Approval> findApproval(UUID id) {
        var review=db.one("select * from ai_resource_review where resource_id=?",id);
        if(review==null || review.get("revoked_at")!=null || !Boolean.TRUE.equals(review.get("approved_public_ai_evidence"))
                || !Boolean.TRUE.equals(review.get("injection_reviewed")))return Optional.empty();
        var doc=document(id); Instant now=clock.instant();
        if(!importCurrent(id,now))return Optional.empty();
        if(!reviewable(doc,now)
                || !doc.revisionFingerprint().equals(review.get("revision_fingerprint"))
                || !doc.getAgencyName().equals(review.get("source_organization")) || !doc.getSourceUrl().equals(review.get("source_url"))
                || now.isBefore(OwnedDatabase.instant(review.get("reviewed_at"))) || !now.isBefore(OwnedDatabase.instant(review.get("valid_until"))))return Optional.empty();
        return Optional.of(new Approval(id,doc.revisionFingerprint(),OwnedDatabase.instant(review.get("reviewed_at")),OwnedDatabase.instant(review.get("valid_until")),true));
    }
    /** Approval cannot weaken the exact source/content checks applied during retrieval. */
    private static boolean reviewable(OfficialResourceDocument doc,Instant now) {
        if(doc==null||doc.getCheckedAt()==null||doc.getCheckedAt().isAfter(now)||doc.getContentText()==null
                ||doc.getContentText().isBlank()||INJECTION.matcher(doc.getContentText()).find()
                ||KoreanLexicalParagraphSearch.suspicious(doc.getContentText()))return false;
        try {
            var uri=OfficialGuidanceImporter.officialUri(doc.getSourceUrl());
            return OfficialGuidanceImporter.publisherMatches(doc.getAgencyName(),uri);
        }catch(RuntimeException invalid){return false;}
    }
    private boolean importCurrent(UUID id,Instant now) {
        if(!db.tableExists("owned_resource_import"))return true;
        var row=db.one("select superseded_at,expires_at from owned_resource_import where resource_id=?",id);
        return row==null || (row.get("superseded_at")==null && now.isBefore(OwnedDatabase.instant(row.get("expires_at"))));
    }
    private void requireReviewer(MemberPrincipal member) {
        if(member==null)throw ApiProblem.unauthorized();
        sessions.requireActiveSession(member.memberId(),member.sessionId());

        if(!((List<?>)auth.me(member).get("roles")).contains("OPS"))throw ApiProblem.forbidden();
    }
    /** No public endpoint or automatic grant. Human review must attest rights, public AI use and injection safety. */
    @Transactional public void review(MemberPrincipal reviewer,UUID resource,String expectedFingerprint,Instant validUntil,
            String evidenceReference,boolean rightsAndPublicAiUseConfirmed,boolean injectionReviewed) {
        if(reviewer==null)throw ApiProblem.unauthorized();
        sessions.runWithActiveSession(reviewer.memberId(),reviewer.sessionId(),()->{
        requireReviewer(reviewer);
        db.one("select resource_id from official_resource where resource_id=? for update",resource);
        var doc=document(resource);
        if(!importCurrent(resource,clock.instant()) || !reviewable(doc,clock.instant()) || !doc.revisionFingerprint().equals(expectedFingerprint) || validUntil==null || !validUntil.isAfter(clock.instant())
                || evidenceReference==null || evidenceReference.isBlank() || evidenceReference.length()>500
                || !rightsAndPublicAiUseConfirmed || !injectionReviewed || doc.getContentText()==null || INJECTION.matcher(doc.getContentText()).find())
            throw new IllegalArgumentException("An exact reviewed publication and explicit evidence are required");
        db.update("""
            insert into ai_resource_review(resource_id,revision_fingerprint,source_organization,source_url,reviewed_at,valid_until,
                approved_public_ai_evidence,revoked_at,evidence_reference,injection_reviewed) values(?,?,?,?,?,?,true,null,?,true)
            on conflict(resource_id) do update set revision_fingerprint=excluded.revision_fingerprint,source_organization=excluded.source_organization,
                source_url=excluded.source_url,reviewed_at=excluded.reviewed_at,valid_until=excluded.valid_until,
                approved_public_ai_evidence=true,revoked_at=null,evidence_reference=excluded.evidence_reference,injection_reviewed=true
            """,resource,expectedFingerprint,doc.getAgencyName(),doc.getSourceUrl(),clock.instant(),validUntil,evidenceReference);
        });
    }

    @Transactional public void revoke(MemberPrincipal reviewer,UUID resource) {
        if(reviewer==null)throw ApiProblem.unauthorized();
        sessions.runWithActiveSession(reviewer.memberId(),reviewer.sessionId(),()->{
            requireReviewer(reviewer);db.update("update ai_resource_review set revoked_at=? where resource_id=?",clock.instant(),resource);
        });
    }
}

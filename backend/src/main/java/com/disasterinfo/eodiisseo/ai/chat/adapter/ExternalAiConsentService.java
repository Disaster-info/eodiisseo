package com.disasterinfo.eodiisseo.ai.chat.adapter;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOutboundConsentPort;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

/** Consent is bounded by its verified granting session; logout/withdrawal/expiry invalidates it without auth hooks. */
@Service @Profile("!test")
public class ExternalAiConsentService implements ChatOutboundConsentPort {
    private final OwnedDatabase db; private final SessionAccessService sessions; private final Clock clock; private final boolean enabled;
    public ExternalAiConsentService(OwnedDatabase db,SessionAccessService sessions,Clock clock,
            @Value("${eodiisseo.ai.external-processing.enabled:false}") boolean enabled) {
        this.db=db;this.sessions=sessions;this.clock=clock;this.enabled=enabled;
    }
    public boolean permits(UUID member) {
        if(!enabled || member==null)return false;
        var consent=db.one("select granted,granted_session_id from ai_external_consent where member_id=?",member);
        if(consent==null || !Boolean.TRUE.equals(consent.get("granted")) || consent.get("granted_session_id")==null)return false;
        try {sessions.requireActiveSession(member,(UUID)consent.get("granted_session_id"));return true;}
        catch(ApiProblem denied){return false;}
    }
    @Transactional public void record(UUID member,UUID session,boolean granted) {
        sessions.runWithActiveSession(member,session,()->db.update("""
            insert into ai_external_consent(member_id,granted,changed_at,granted_session_id) values(?,?,?,?)
            on conflict(member_id) do update set granted=excluded.granted,changed_at=excluded.changed_at,
                granted_session_id=excluded.granted_session_id
            """,member,granted,clock.instant(),granted?session:null));
    }
    /** Explicit owned-data cleanup only. The authentication owner has not connected a lifecycle callback. */
    @Transactional public void discardOnWithdrawal(UUID member) {db.update("delete from ai_external_consent where member_id=?",member);}
}

package com.disasterinfo.eodiisseo.disaster.service;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Atomic outbox writer. Caller owns the official disaster write transaction. */
@Service @Profile("!test")
public class DisasterRuntimeEvents {
    private final OwnedDatabase db;
    public DisasterRuntimeEvents(OwnedDatabase db){this.db=db;}
    public void append(UUID disaster,UUID source,UUID revision,String kind,UUID previous,UUID current,String key,Instant at) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("An official transaction is required");
        if(disaster==null || kind==null || key==null || key.isBlank() || key.length()>100 || at==null)throw new IllegalArgumentException("Invalid official event");
        db.update("""
            insert into disaster_runtime_outbox(event_id,disaster_id,source_disaster_id,revision_id,event_kind,
                previous_severity_id,current_severity_id,event_key,occurred_at,created_at,available_at)
            select ?,d.disaster_id,?,?,?, ?,d.official_severity_id,?,d.occurred_at,?,? from disaster d where d.disaster_id=? and d.data_origin='OFFICIAL'
            on conflict(event_key) do nothing
            """,UUID.randomUUID(),source,revision,kind,previous,key,at,at,disaster);
    }
    @Transactional
    public boolean changeOfficialSeverity(UUID disaster,UUID next,Instant at) {
        var old=db.one("select status,data_origin,official_severity_id from disaster where disaster_id=? for update",disaster);
        if(old==null || !"OFFICIAL".equals(old.get("data_origin")))return false;
        UUID previous=(UUID)old.get("official_severity_id");
        if(Objects.equals(previous,next))return false;
        var newSeverity=next==null?null:db.one("select rank from severity_level where severity_id=?",next);
        if(next!=null&&newSeverity==null)throw new IllegalArgumentException("Unknown official severity");
        var prior=previous==null?null:db.one("select rank from severity_level where severity_id=?",previous);
        db.update("update disaster set official_severity_id=?,updated_at=? where disaster_id=?",next,at,disaster);
        UUID change=UUID.randomUUID();
        db.update("insert into disaster_change(change_id,disaster_id,change_kind,new_value,changed_at) values(?,?,'SEVERITY_CHANGED',?::jsonb,?)",
            change,disaster,"{\"severityId\":"+(next==null?"null":"\""+next+"\"")+"}",at);
        if(prior!=null && newSeverity!=null && ((Number)newSeverity.get("rank")).intValue()>((Number)prior.get("rank")).intValue()
                && Set.of("OCCURRED","ONGOING").contains(old.get("status"))) {
            var revision=db.one("select s.source_disaster_id,r.revision_id from source_disaster s join source_disaster_revision r using(source_disaster_id) where s.disaster_id=? order by r.observed_at desc,r.revision_id desc limit 1",disaster);
            append(disaster,revision==null?null:(UUID)revision.get("source_disaster_id"),revision==null?null:(UUID)revision.get("revision_id"),
                "SEVERITY_INCREASE",previous,next,"SEVERITY:"+change,at);
        }
        return true;
    }
}

package com.disasterinfo.eodiisseo.disaster.service;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;

/** FK-safe 90-day cleanup; an active runtime claim delays deletion instead of racing work. */
@Service @Profile("!test")
public class DisasterRetentionService {
    private final OwnedDatabase db;
    public DisasterRetentionService(OwnedDatabase db){this.db=db;}
    @Transactional public boolean purgeOne(Instant now) {
        var row=db.one("select disaster_id from disaster where status in ('ENDED','LIFTED') and ended_at<=? order by ended_at,disaster_id for update skip locked limit 1",now.minus(Duration.ofDays(90)));
        if(row==null)return false;
        UUID id=(UUID)row.get("disaster_id");
        var events=db.rows("select state,lease_until from disaster_runtime_outbox where disaster_id=? for update",id);
        var tasks=db.rows("select state,lease_until from summary_runtime_task where revision_id in (select r.revision_id from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=?) for update",id);
        for(var state:java.util.stream.Stream.concat(events.stream(),tasks.stream()).toList())
            if("WORKING".equals(state.get("state")) && state.get("lease_until")!=null && OwnedDatabase.instant(state.get("lease_until")).isAfter(now))return false;
        db.update("delete from summary_runtime_task where revision_id in (select r.revision_id from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=?)",id);
        db.update("delete from disaster_runtime_outbox where disaster_id=?",id);
        db.update("delete from ai_summary where disaster_id=?",id);
        db.update("delete from disaster_area where disaster_id=?",id);
        db.update("delete from source_disaster_revision where source_disaster_id in (select source_disaster_id from source_disaster where disaster_id=?)",id);
        db.update("delete from source_disaster where disaster_id=?",id);
        db.update("delete from disaster_change where disaster_id=?",id);
        // Canonical ON DELETE SET NULL retains existing notification history/deadlines.
        db.update("delete from disaster where disaster_id=?",id);
        return true;
    }
}

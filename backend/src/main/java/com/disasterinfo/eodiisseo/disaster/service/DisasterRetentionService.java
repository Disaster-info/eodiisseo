package com.disasterinfo.eodiisseo.disaster.service;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

/** FK-safe 90-day cleanup for incidents whose runtime work is complete. */
@Service @Profile("!test")
public class DisasterRetentionService {
    private final OwnedDatabase db;
    public DisasterRetentionService(OwnedDatabase db){this.db=db;}
    @Transactional public boolean purgeOne(Instant now) {
        // The projection schema is optional. Its append-only receipts retain their source revision.
        boolean projectionInstalled=db.tableExists("collection_projection_receipt");
        String receiptGuard=projectionInstalled
                ? " and not exists (select 1 from collection_projection_receipt p where p.disaster_id=d.disaster_id)"
                : "";
        var row=db.one("""
            select d.disaster_id from disaster d
            where d.status in ('ENDED','LIFTED') and d.ended_at<=?
              and not exists (select 1 from disaster_runtime_outbox e where e.disaster_id=d.disaster_id and e.state<>'DONE')
              and not exists (
                  select 1 from summary_runtime_task t
                  join source_disaster_revision r using(revision_id)
                  join source_disaster s using(source_disaster_id)
                  where s.disaster_id=d.disaster_id and t.state<>'DONE'
              )
            """+receiptGuard+" order by d.ended_at,d.disaster_id for update of d skip locked limit 1",
                now.minus(Duration.ofDays(90)));
        if(row==null)return false;
        UUID id=(UUID)row.get("disaster_id");
        var events=db.rows("select state from disaster_runtime_outbox where disaster_id=? for update",id);
        var tasks=db.rows("select state from summary_runtime_task where revision_id in (select r.revision_id from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=?) for update",id);
        for(var state:java.util.stream.Stream.concat(events.stream(),tasks.stream()).toList())
            if(!"DONE".equals(state.get("state")))return false;
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

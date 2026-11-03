package com.disasterinfo.eodiisseo.ai.summary.adapter;

import com.disasterinfo.eodiisseo.ai.summary.port.SummaryTaskDispatcher;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

@Component @Profile("!test")
public class DurableSummaryTasks implements SummaryTaskDispatcher {
    private final OwnedDatabase db;
    public DurableSummaryTasks(OwnedDatabase db){this.db=db;}
    @Override @Transactional public void enqueue(UUID revision,Instant at) {
        db.update("""
            insert into summary_runtime_task(revision_id,available_at) values(?,?)
            on conflict(revision_id) do update set available_at=excluded.available_at,state='PENDING',
                claim_id=null,lease_until=null,last_error_code=null
            where summary_runtime_task.state='WORKING'
            """,revision,at);
    }
}

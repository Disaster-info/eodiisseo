package com.disasterinfo.eodiisseo.disaster.service;

import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.ai.summary.*;
import com.disasterinfo.eodiisseo.ai.summary.adapter.DurableSummaryTasks;
import com.disasterinfo.eodiisseo.ai.summary.port.*;
import com.disasterinfo.eodiisseo.notification.*;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short claim/finish transactions only. Network and generation never run in the outbox claim transaction. */
@Service @Profile("!test")
public class DisasterRuntimeWorker {
    private final OwnedDatabase db;
    private final SummaryService summaries;
    private final DurableSummaryTasks tasks;
    private final NotificationDispatchService notifications;
    private final Optional<SummaryGenerator> generator;
    private final SummaryStore summaryStore;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    public DisasterRuntimeWorker(OwnedDatabase db,SummaryService summaries,DurableSummaryTasks tasks,
            NotificationDispatchService notifications,Optional<SummaryGenerator> generator,SummaryStore summaryStore,PlatformTransactionManager manager,
            Clock clock,@Value("${eodiisseo.runtime.enabled:false}") boolean enabled) {
        this.db=db;this.summaries=summaries;this.tasks=tasks;this.notifications=notifications;
        this.generator=generator;this.summaryStore=summaryStore;this.tx=new TransactionTemplate(manager);this.clock=clock;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${eodiisseo.runtime.poll-ms:1000}")
    public void tick(){if(enabled){drainOneEvent();drainOneSummary();}}
    private Map<String,Object> claim(String table,String id) {
        return tx.execute(status->{
            UUID claim=UUID.randomUUID(); Instant now=clock.instant();
            return db.one("update "+table+" set state='WORKING',claim_id=?,lease_until=?,attempts=attempts+1 where "+id+
                "=(select "+id+" from "+table+" where (state in ('PENDING','HELD') and available_at<=?) or (state='WORKING' and lease_until<=?) order by available_at,"+id+
                " for update skip locked limit 1) returning *",claim,now.plus(Duration.ofMinutes(20)),now,now);
        });
    }
    private void finish(String table,String id,Map<String,Object> row,String state,String code) {
        tx.executeWithoutResult(status->db.update("update "+table+" set state=?,last_error_code=?,available_at=?,lease_until=null,claim_id=null,completed_at=? where "+id+"=? and claim_id=?",
            state,code,clock.instant().plusSeconds(60),"DONE".equals(state)?clock.instant():null,row.get(id),row.get("claim_id")));
    }
    public boolean drainOneEvent() {
        var row=claim("disaster_runtime_outbox","event_id"); if(row==null)return false;
        try {
            if("REVISION".equals(row.get("event_kind"))) {
                var outcome=summaries.onOfficialRevisionSaved((UUID)row.get("revision_id"),OwnedDatabase.instant(row.get("created_at")));
                if(outcome==SummaryService.Submission.POLICY_UNCONFIGURED
                        || (outcome==SummaryService.Submission.DEPENDENCY_UNAVAILABLE && summaryStore.findByRevision((UUID)row.get("revision_id")).isEmpty())) {
                    finish("disaster_runtime_outbox","event_id",row,"HELD","REGISTRATION_UNAVAILABLE");return true;
                }
                // Persist the waiting task even with no active model; PENDING is externally visible.
                if(outcome==SummaryService.Submission.DEPENDENCY_UNAVAILABLE || outcome==SummaryService.Submission.QUEUED)
                    tasks.enqueue((UUID)row.get("revision_id"),clock.instant());
                if(outcome==SummaryService.Submission.SOURCE_INCOMPLETE) {
                    finish("disaster_runtime_outbox","event_id",row,"HELD","SOURCE_INCOMPLETE");return true;
                }
            } else {
                UUID previous=(UUID)row.get("previous_severity_id");
                var severity=previous==null?null:db.one("select code,rank from severity_level where severity_id=?",previous);
                Severity old=severity==null?null:new Severity(previous,(String)severity.get("code"),((Number)severity.get("rank")).intValue());
                var report=notifications.process(new DisasterChange((UUID)row.get("disaster_id"),
                    "NEW".equals(row.get("event_kind"))?ChangeKind.NEW:ChangeKind.CHANGED,old,(String)row.get("event_key")),clock.instant());
                if(!report.suppressionReasons().isEmpty()) {
                    String reasons=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(report.suppressionReasons());
                    tx.executeWithoutResult(status->db.update("update disaster_runtime_outbox set suppression_reasons=?::jsonb where event_id=? and claim_id=?",reasons,row.get("event_id"),row.get("claim_id")));
                }
                if(report.connected() && report.internalFailures()==0 && report.createdEvents()==0) {
                    finish("disaster_runtime_outbox","event_id",row,"DONE","SUPPRESSED_POLICY_OR_DUPLICATE");return true;
                }
                if(!report.connected() || report.internalFailures()>0) {
                    finish("disaster_runtime_outbox","event_id",row,"HELD","NOTIFICATION_UNAVAILABLE");return true;
                }
            }
            finish("disaster_runtime_outbox","event_id",row,"DONE",null);
        } catch(RuntimeException failure){finish("disaster_runtime_outbox","event_id",row,"HELD","DEPENDENCY_UNAVAILABLE");}
        return true;
    }
    public boolean drainOneSummary() {
        var row=claim("summary_runtime_task","revision_id");if(row==null)return false;
        try {
            if(generator.isEmpty() || !generator.get().isConfigured()) {
                finish("summary_runtime_task","revision_id",row,"HELD","MODEL_DISABLED");return true;
            }
            var result=summaries.processRevision((UUID)row.get("revision_id"),clock.instant());
            if(result==SummaryService.Processing.COMPLETED || result==SummaryService.Processing.STALE || result==SummaryService.Processing.NOT_ELIGIBLE)
                finish("summary_runtime_task","revision_id",row,"DONE",null);
            else finish("summary_runtime_task","revision_id",row,"HELD",result.name());
        } catch(RuntimeException failure){finish("summary_runtime_task","revision_id",row,"HELD","PROCESSING_FAILED");}
        return true;
    }
}

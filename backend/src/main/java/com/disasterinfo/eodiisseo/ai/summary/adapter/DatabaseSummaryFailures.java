package com.disasterinfo.eodiisseo.ai.summary.adapter;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryFailureRecorder;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.*;
/** Sanitized durable diagnostics; never persist exception text, prompts, keys or source content. */
@Component @Profile("!test")
public class DatabaseSummaryFailures implements SummaryFailureRecorder {
    private final JdbcTemplate sql;
    public DatabaseSummaryFailures(JdbcTemplate sql){this.sql=sql;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(Code code,UUID disaster,UUID revision,Instant at) {
        sql.update("insert into service_error_log(error_id,run_id,component,error_code,safe_message,occurred_at) values(?,null,'AISUM',?,?,?)",
            UUID.randomUUID(),code.name(),"Summary operation failed; revision="+revision,Timestamp.from(at));
    }
}

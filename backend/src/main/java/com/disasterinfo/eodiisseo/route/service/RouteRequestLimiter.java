package com.disasterinfo.eodiisseo.route.service;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared across server instances; failed TMAP requests also consume the request budget. */
@Service @Profile("!test")
public class RouteRequestLimiter {
    private final JdbcTemplate jdbc;private final Clock clock;private final TransactionTemplate tx;
    public RouteRequestLimiter(JdbcTemplate jdbc,Clock clock,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.clock=clock;this.tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void check(UUID member){
        if(member==null)throw ApiProblem.unauthorized();
        var window=clock.instant().truncatedTo(ChronoUnit.MINUTES);
        int count=tx.execute(status->{
            jdbc.update("delete from auth_request_window where member_id=? and scope='ROUTE_READ' and window_start<?",member,java.sql.Timestamp.from(window.minusSeconds(3600)));
            return jdbc.queryForObject("""
                insert into auth_request_window(member_id,scope,window_start,request_count) values(?,'ROUTE_READ',?,1)
                on conflict(member_id,scope,window_start) do update set request_count=auth_request_window.request_count+1
                returning request_count
                """,Integer.class,member,java.sql.Timestamp.from(window));
        });
        if(count>10)throw new ApiProblem(429,"RATE_LIMITED","경로 조회는 회원당 분당 10회까지 가능합니다.");
    }
}

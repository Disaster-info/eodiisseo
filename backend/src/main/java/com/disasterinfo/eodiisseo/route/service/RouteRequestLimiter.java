package com.disasterinfo.eodiisseo.route.service;

import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.common.exception.BusinessException;
import com.disasterinfo.eodiisseo.common.exception.ErrorCode;
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
        check(member,"ROUTE_READ",10);
    }
    public void checkPlaces(UUID member){
        check(member,"PLACE_READ",30);
    }
    private void check(UUID member,String scope,int limit){
        if(member==null)throw ApiProblem.unauthorized();
        var window=clock.instant().truncatedTo(ChronoUnit.MINUTES);
        int count=tx.execute(status->{
            jdbc.update("delete from auth_request_window where member_id=? and scope=? and window_start<?",member,scope,java.sql.Timestamp.from(window.minusSeconds(3600)));
            return jdbc.queryForObject("""
                insert into auth_request_window(member_id,scope,window_start,request_count) values(?,?,?,1)
                on conflict(member_id,scope,window_start) do update set request_count=auth_request_window.request_count+1
                returning request_count
                """,Integer.class,member,scope,java.sql.Timestamp.from(window));
        });
        if(count>limit&&scope.equals("PLACE_READ"))throw new BusinessException(ErrorCode.RATE_LIMITED);
        if(count>limit)throw new ApiProblem(429,"RATE_LIMITED","경로 조회는 회원당 분당 10회까지 가능합니다.");
    }
}

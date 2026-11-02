package com.disasterinfo.eodiisseo.auth.service;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.repository.RateLimitRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** A shared database window counts failed invite-code attempts across all server instances. */
@Component @Profile("!test")
public class OwnedRateLimiter {
    @org.springframework.beans.factory.annotation.Value("${LOCATION_WRITE_REQUESTS_PER_MINUTE:60}")
    private int locationLimit=60;
    private final RateLimitRepository repository;private final Clock clock;private final TransactionTemplate transaction;
    public OwnedRateLimiter(RateLimitRepository repository,Clock clock,PlatformTransactionManager manager) {
        this.repository=repository;this.clock=clock;transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void checkGroupJoin(UUID member) {
        var window=clock.instant().truncatedTo(ChronoUnit.MINUTES);
        int count=transaction.execute(tx->{
            repository.deleteExpiredWindows(member,window.minusSeconds(3600));
            var row=repository.incrementGroupJoinWindow(member,window);
            return ((Number)row.get("request_count")).intValue();
        });
        if(count>10) throw new ApiProblem(429,"RATE_LIMITED","잠시 후 다시 참여를 시도하세요.");
    }
    public void checkLocationWrite(UUID member) {
        var window=clock.instant().truncatedTo(ChronoUnit.MINUTES);
        int count=transaction.execute(tx->{
            repository.deleteExpiredWindows(member,window.minusSeconds(3600));
            return ((Number)repository.incrementLocationWindow(member,window).get("request_count")).intValue();
        });
        if(count>Math.max(1,locationLimit)) throw new ApiProblem(429,"RATE_LIMITED","위치 갱신 요청이 너무 많습니다.");
    }
}

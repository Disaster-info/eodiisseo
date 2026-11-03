package com.disasterinfo.eodiisseo.area.config;

import com.disasterinfo.eodiisseo.area.adapter.AreaNotificationInterestAreas;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.disaster.adapter.NormalizedDisasterReader;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterReader;
import com.disasterinfo.eodiisseo.group.repository.GroupRepository;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

/** Domain defaults are evaluated after explicit application/test adapters, one port at a time. */
@AutoConfiguration @Profile("!test")
@ConditionalOnBean(OwnedDatabase.class)
public class DomainReadAutoConfiguration {
    @Bean @ConditionalOnMissingBean(com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterQueryReader.class)
    public com.disasterinfo.eodiisseo.dashboard.port.DashboardDisasterQueryReader domainDisasterQueries(OwnedDatabase db,Clock clock){
        return new com.disasterinfo.eodiisseo.disaster.adapter.JdbcDisasterQueryReader(db,clock);
    }
    @Bean @ConditionalOnMissingBean(InterestAreas.class)
    public InterestAreas domainInterestAreas(OwnedDatabase db){return new AreaNotificationInterestAreas(db);}
    private NormalizedDisasterReader reader(OwnedDatabase db,GroupRepository groups,Clock clock,long stale){
        return new NormalizedDisasterReader(db,groups,new AreaNotificationInterestAreas(db),clock,stale);
    }
    @Bean @ConditionalOnMissingBean(DashboardDisasterReader.class)
    public DashboardDisasterReader domainDashboardDisasters(OwnedDatabase db,GroupRepository groups,Clock clock,@Value("${LOCATION_STALE_AFTER_SECONDS:300}") long stale){
        var reader=reader(db,groups,clock,stale);return reader::findCandidates;
    }
    @Bean @ConditionalOnMissingBean(DisasterReader.class)
    public DisasterReader domainNotificationDisasters(OwnedDatabase db,GroupRepository groups,Clock clock,@Value("${LOCATION_STALE_AFTER_SECONDS:300}") long stale){
        var reader=reader(db,groups,clock,stale);return reader::findOfficial;
    }
    @Bean @ConditionalOnMissingBean(SeverityReader.class)
    public SeverityReader domainSeverities(OwnedDatabase db,GroupRepository groups,Clock clock,@Value("${LOCATION_STALE_AFTER_SECONDS:300}") long stale){
        var reader=reader(db,groups,clock,stale);
        return new SeverityReader(){public Severity findByCode(String code){return reader.findByCode(code);}public Severity findById(UUID id){return reader.findById(id);}};
    }
}

package com.disasterinfo.eodiisseo.disaster.adapter;

import com.disasterinfo.eodiisseo.area.adapter.AreaNotificationInterestAreas;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.disaster.service.OfficialDisasterQueryService;
import com.disasterinfo.eodiisseo.dashboard.*;
import com.disasterinfo.eodiisseo.dashboard.port.*;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import com.disasterinfo.eodiisseo.group.repository.GroupRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/** Register owned defaults after explicit application/test Ports, without editing another owner's configuration. */
public final class OwnedDisasterPortDefaults {
    private OwnedDisasterPortDefaults() {}
    public static class Selector implements DeferredImportSelector, Ordered {
        public int getOrder(){return Ordered.HIGHEST_PRECEDENCE;}
        public String[] selectImports(AnnotationMetadata metadata){return new String[]{Bindings.class.getName()};}
    }
    // Deliberately not a scanned @Configuration: only the opt-in deferred import registers these Beans.
    public static class Bindings {
        @Bean @ConditionalOnMissingBean(DashboardDisasterReader.class)
        public DashboardDisasterReader ownedDashboardDisasters(OfficialDisasterQueryService data, OwnedDatabase db,
                GroupRepository groups, Clock clock, @Value("${LOCATION_STALE_AFTER_SECONDS:300}") long staleSeconds) {
            var regional = new NormalizedDisasterReader(db,groups,new AreaNotificationInterestAreas(db),clock,staleSeconds);
            return (member,criteria,base)->{
                if(criteria.basis()!=DashboardQuery.Basis.NATIONWIDE)
                    return regional.findCandidates(member,criteria,base);
                var rows=data.activeNationwide();
                var regions=data.displayRegions(rows);
                return rows.stream().map(row->new DashboardDisasterReader.Candidate(
                    DisasterOperationalAdapters.major(row,regions),DashboardDisasterReader.DataOrigin.OFFICIAL,
                    DashboardDisasterReader.Relevance.UNKNOWN)).toList();
            };
        }
        @Bean @ConditionalOnMissingBean(SeverityReader.class)
        public SeverityReader ownedSeverityCatalog(OfficialDisasterQueryService data) {
            return new SeverityReader() {
                private Severity value(Optional<Map<String,Object>> row) {
                    return row.map(r->new Severity((UUID)r.get("severity_id"),(String)r.get("code"),((Number)r.get("rank")).intValue())).orElse(null);
                }
                public Severity findById(UUID id){return value(data.severityById(id));}
                public Severity findByCode(String code){return value(data.severityByCode(code));}
            };
        }
        @Bean @ConditionalOnMissingBean(DisasterReader.class)
        public DisasterReader ownedNotificationDisasters(OfficialDisasterQueryService data,SeverityReader severity) {
            return id->{
                var row=data.find(id).orElse(null);var source=data.representative(id).orElse(null);
                if(row==null || source==null || !data.latest(source))return null;
                String region=data.notificationRegion(id);
                return new OfficialDisaster(id,(String)row.get("type_code"),region,(String)row.get("title"),
                    (String)source.get("revision_text"),severity.findById((UUID)row.get("official_severity_id")),
                    DisasterStatus.valueOf((String)row.get("status")),true);
            };
        }
    }
}

package com.disasterinfo.eodiisseo.disaster.adapter;

import com.disasterinfo.eodiisseo.disaster.service.OfficialDisasterQueryService;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.dashboard.port.*;
import com.disasterinfo.eodiisseo.dashboard.dto.*;
import com.disasterinfo.eodiisseo.dashboard.dto.DashboardResponse.*;
import com.disasterinfo.eodiisseo.ai.summary.*;
import com.disasterinfo.eodiisseo.ai.summary.port.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false) @Profile("!test")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="eodiisseo.owned-runtime.enabled",havingValue="true")
@Import(OwnedDisasterPortDefaults.Selector.class)
public class DisasterOperationalAdapters {
    @Bean public DashboardDisasterQueryReader operationalDisasterQueries(OfficialDisasterQueryService data) {
        return new DashboardDisasterQueryReader() {
            public boolean existsType(String code){return data.existsType(code);}
            public ListSnapshot findDisasters(DisasterListQuery.Criteria c) {
                var entries=data.list(c.type(),c.status()==null?null:c.status().name(),c.page(),c.size()).stream()
                    .map(row->new Entry(major(data,row),DashboardDisasterReader.DataOrigin.OFFICIAL)).toList();
                return new ListSnapshot(entries,true,true);
            }
            public Optional<DetailSnapshot> findDisaster(UUID id) {
                return data.find(id).map(row->{
                    var source=data.representative(id).orElseThrow(()->new com.disasterinfo.eodiisseo.dashboard.DashboardException(
                        com.disasterinfo.eodiisseo.dashboard.DashboardException.Reason.DEPENDENCY_UNAVAILABLE,"Official source is unavailable."));

                    return new DetailSnapshot(new DisasterDetailResponse(id,(String)row.get("type_code"),(String)row.get("title"),
                        data.region(id),OwnedDatabase.instant(row.get("occurred_at")),(String)row.get("severity_code"),
                        DisasterStatus.valueOf((String)row.get("status")),(String)source.get("revision_text"),
                        new DisasterDetailResponse.Source((String)source.get("organization")),
                        OwnedDatabase.instant(row.get("updated_at")),data.latest(source)?"LATEST":"STALE"),DashboardDisasterReader.DataOrigin.OFFICIAL);
                });
            }
        };
    }
    static MajorDisaster major(OfficialDisasterQueryService data,Map<String,Object> row) {
        return new MajorDisaster((UUID)row.get("disaster_id"),(String)row.get("type_code"),(String)row.get("title"),
            data.region((UUID)row.get("disaster_id")),DisasterStatus.valueOf((String)row.get("status")),
            (String)row.get("severity_code"),OwnedDatabase.instant(row.get("occurred_at")),OwnedDatabase.instant(row.get("updated_at")));
    }
    @Bean public SummarySourceReader operationalSummarySources(OfficialDisasterQueryService data) {
        return new SummarySourceReader() {
            public Optional<Source> findDisaster(UUID id) {
                return data.find(id).map(row->new Source(id,OfficialRevision.Origin.OFFICIAL,
                    data.representative(id).flatMap(s->findRevision((UUID)s.get("revision_id")))));
            }
            public Optional<OfficialRevision> findRevision(UUID id) {
                return data.revision(id).map(r->new OfficialRevision((UUID)r.get("disaster_id"),(UUID)r.get("source_disaster_id"),
                    id,((Number)r.get("revision_no")).intValue(),(String)r.get("type_code"),data.region((UUID)r.get("disaster_id")),
                    OfficialRevision.DisasterStatus.valueOf((String)r.get("status")),(String)r.get("severity_code"),
                    (String)r.get("original_text"),OwnedDatabase.instant(r.get("observed_at")),OfficialRevision.Origin.OFFICIAL));
            }
            public boolean isCurrentRevision(UUID id){return data.isCurrent(id);}
            public List<EndedDisaster> findEndedAtOrBefore(Instant cutoff) {
                return data.ended(cutoff).stream().map(r->new EndedDisaster((UUID)r.get("disaster_id"),
                    OfficialRevision.DisasterStatus.valueOf((String)r.get("status")),OwnedDatabase.instant(r.get("ended_at")))).toList();
            }
        };
    }
}

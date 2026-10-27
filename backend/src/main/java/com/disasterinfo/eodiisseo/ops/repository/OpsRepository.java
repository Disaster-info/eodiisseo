package com.disasterinfo.eodiisseo.ops.repository;

import java.util.*;
import java.time.Instant;
import com.disasterinfo.eodiisseo.ops.dto.OpsDto.PageResult;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;

/** SQL and bind parameters for the ops feature. Transactions are owned by the service. */
@Repository @Profile("!test")
public class OpsRepository {
    private final OwnedDatabase db;
    public OpsRepository(OwnedDatabase db) { this.db=db; }

    public List<Map<String,Object>> listApiStatuses(String statusMode, String successMode) {
        return db.rows("select s.endpoint_key as \"apiName\",coalesce((select r.status from collection_run r where r.source_id=s.source_id and r.mode=? order by r.started_at desc,r.run_id desc limit 1),'UNKNOWN') as status,(select max(r.finished_at) from collection_run r where r.source_id=s.source_id and r.mode=? and r.status='SUCCESS') as \"lastSuccessfulCollectedAt\" from data_source s where s.active=true order by s.endpoint_key",statusMode,successMode);
    }

    private long countDisasters(String where, Object... args) {
        return db.count("select count(*) from disaster d join disaster_type t using(disaster_type_id)"+where,args);
    }

    private List<Map<String,Object>> listDisasters(String where, Object... args) {
        return db.rows("select d.disaster_id as \"disasterId\",t.code as type,d.title,d.status,d.occurred_at as \"occurredAt\",d.updated_at as \"updatedAt\",(select max(sd.collected_at) from source_disaster sd where sd.disaster_id=d.disaster_id) as \"collectedAt\",(select string_agg(distinct a.name,', ' order by a.name) from disaster_area da join admin_area a using(area_code) where da.disaster_id=d.disaster_id) as region,(select string_agg(distinct s.name,', ' order by s.name) from source_disaster sd join data_source s using(source_id) where sd.disaster_id=d.disaster_id) as source from disaster d join disaster_type t using(disaster_type_id)"+where+" order by d.occurred_at desc,d.disaster_id desc limit ? offset ?",args);
    }

    private long countErrorLogs(String joins, String where, Object... args) {
        return db.count("select count(*)"+joins+where,args);
    }

    private List<Map<String,Object>> listErrorLogs(String joins, String where, Object... args) {
        return db.rows("select e.error_id as \"logId\",coalesce(s.endpoint_key,e.component) as source,e.error_code as \"errorType\",e.occurred_at as \"occurredAt\""+joins+where+" order by e.occurred_at desc,e.error_id desc limit ? offset ?",args);
    }

    public long countActiveShelterSource(String source) {
        return db.count("select count(*) from data_source where endpoint_key=? and source_kind='SHELTER' and active=true",source);
    }

    public boolean tableExists(String table) { return db.tableExists(table); }

    public PageResult findDisasterPage(String type, String status, Instant from, Instant to, int page, int size, String mode) {
        var args=new ArrayList<Object>();args.add(mode.equals("LIVE")?"OFFICIAL":"MOCK");var where=new StringBuilder(" where d.data_origin=?");
        if(type!=null) {where.append(" and t.code=?");args.add(type);}if(status!=null) {where.append(" and d.status=?");args.add(status);}
        interval(where,args,"d.occurred_at",from,to);
        var total=countDisasters(where.toString(), args.toArray());
        args.add(size);args.add((long)page*size);
        var rows=listDisasters(where.toString(), args.toArray());
        return new PageResult(rows,total);
    }

    public PageResult findErrorPage(String source, Instant from, Instant to, int page, int size, String mode) {
        var args=new ArrayList<Object>(List.of(mode,mode));var where=new StringBuilder(" where (r.mode=? or (e.run_id is null and ?='LIVE'))");
        if(source!=null) {where.append(" and coalesce(s.endpoint_key,e.component)=?");args.add(source);}interval(where,args,"e.occurred_at",from,to);
        var joins=" from service_error_log e left join collection_run r using(run_id) left join data_source s using(source_id)";
        var total=countErrorLogs(joins, where.toString(), args.toArray());args.add(size);args.add((long)page*size);
        var rows=listErrorLogs(joins, where.toString(), args.toArray());
        return new PageResult(rows,total);
    }
    private void interval(StringBuilder sql,List<Object> args,String field,Instant from,Instant to) { if(from!=null) {sql.append(" and ").append(field).append(">=?");args.add(from);}if(to!=null) {sql.append(" and ").append(field).append("<?");args.add(to);} }
}

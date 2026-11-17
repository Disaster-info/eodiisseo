package com.disasterinfo.eodiisseo.auth.repository;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database access used only by the five assigned features. No foreign module implementation is replaced. */
@Component @Profile("!test")
public class OwnedDatabase {
    private final JdbcTemplate jdbc;
    public OwnedDatabase(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Object[] parameters(Object[] args) { return Arrays.stream(args).map(v->v instanceof Instant i ? Timestamp.from(i) : v).toArray(); }
    public List<Map<String,Object>> rows(String sql, Object... args) { return jdbc.queryForList(sql, parameters(args)); }
    public Map<String,Object> one(String sql, Object... args) {
        var list = rows(sql,args); return list.isEmpty() ? null : list.getFirst();
    }
    public int update(String sql, Object... args) { return jdbc.update(sql,parameters(args)); }
    public long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class,parameters(args)); }
    public void lockMembershipChanges() {
        // One transaction-scoped gate orders cross-member/group mutations, including withdrawal.
        jdbc.execute("select pg_advisory_xact_lock(78161106)");
    }
    public void lockMember(UUID id) {
        var m=one("select status from member where member_id=? for update",id);
        if(m==null || !"ACTIVE".equals(m.get("status"))) throw ApiProblem.unauthorized();
    }
    public static Instant instant(Object value) {
        if(value==null) return null;
        if(value instanceof Timestamp t) return t.toInstant();
        if(value instanceof java.time.OffsetDateTime t) return t.toInstant();
        return (Instant)value;
    }
    public static Map<String,Object> object(Object... pairs) {
        var map=new LinkedHashMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2) map.put((String)pairs[i],pairs[i+1]);
        return map;
    }
    public boolean tableExists(String name) {
        return one("select to_regclass(?) as table_name",name).get("table_name")!=null;
    }
}

package com.disasterinfo.eodiisseo.collection.operations;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
/** Inspects pg_roles/ACL/ownership without reading credentials or mutating privileges. */
public final class CollectionDatabasePrivilegeInspector {
    public record Policy(String worker,String ops,String migration,String schemaOwner,String schema){
        public Policy {for(var s:List.of(worker,ops,migration,schemaOwner,schema))if(!s.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Exact role/schema names required");if(Set.of(worker,ops,migration).size()!=3)throw new IllegalArgumentException("Distinct principals required");}
    }
    public record Result(boolean safe,List<String> blockers,String fingerprint) {}
    private final JdbcTemplate jdbc;
    public CollectionDatabasePrivilegeInspector(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Result inspect(Policy p){
        var reasons=new ArrayList<String>();var facts=new ArrayList<Object>();
        for(String name:List.of(p.worker(),p.ops(),p.migration())){
            var rows=jdbc.queryForList("select oid,rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls,rolinherit from pg_roles where rolname=?",name);
            if(rows.size()!=1){reasons.add("ROLE_MISSING");continue;}var r=rows.getFirst();facts.add(r);
            for(String key:List.of("rolsuper","rolcreatedb","rolcreaterole","rolreplication","rolbypassrls","rolinherit"))if(Boolean.TRUE.equals(r.get(key)))reasons.add("EXCESS_ROLE_ATTRIBUTE:"+name+":"+key);
        }
        if(!reasons.isEmpty())return new Result(false,List.copyOf(reasons),CollectionAssurance.hash(CollectionAssurance.canonical(facts)));
        for(String a:List.of(p.worker(),p.ops())){
            if(jdbc.queryForObject("select count(*) from pg_roles r where r.rolname<>? and pg_has_role(?::name,r.oid,'MEMBER')",Long.class,a,a)>0)reasons.add("UNAPPROVED_TRANSITIVE_ROLE_MEMBERSHIP:"+a);
            for(String b:List.of(p.migration(),p.schemaOwner()))if(Boolean.TRUE.equals(jdbc.queryForObject("select pg_has_role(?::name,?::name,'MEMBER')",Boolean.class,a,b)))reasons.add("PRIVILEGED_ROLE_MEMBERSHIP:"+a);
            if(Boolean.TRUE.equals(jdbc.queryForObject("select has_database_privilege(?::name,current_database(),'TEMP')",Boolean.class,a)))reasons.add("APPLICATION_TEMP_SCHEMA_SHADOWING:"+a);
            String other=a.equals(p.worker())?p.ops():p.worker();if(Boolean.TRUE.equals(jdbc.queryForObject("select pg_has_role(?::name,?::name,'MEMBER')",Boolean.class,a,other)))reasons.add("CROSS_ROLE_MEMBERSHIP:"+a);
            if(Boolean.TRUE.equals(jdbc.queryForObject("select has_schema_privilege(?::name,?,'CREATE')",Boolean.class,a,p.schema())))reasons.add("APPLICATION_SCHEMA_CREATE:"+a);
            if(Boolean.TRUE.equals(jdbc.queryForObject("select has_database_privilege(?::name,current_database(),'CREATE')",Boolean.class,a)))reasons.add("APPLICATION_DATABASE_CREATE:"+a);
            if(jdbc.queryForObject("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_roles r on r.oid=c.relowner where n.nspname=? and r.rolname=?",Long.class,p.schema(),a)>0)reasons.add("APPLICATION_OWNS_RELATION:"+a);
            if(jdbc.queryForObject("select count(*) from pg_proc f join pg_namespace n on n.oid=f.pronamespace join pg_roles r on r.oid=f.proowner where n.nspname=? and r.rolname=?",Long.class,p.schema(),a)>0)reasons.add("APPLICATION_OWNS_FUNCTION:"+a);
        }
        if(!p.migration().equals(p.schemaOwner()))reasons.add("MIGRATION_MUST_OWN_SCHEMA");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_namespace n join pg_roles r on r.oid=n.nspowner where n.nspname=? and r.rolname=?)",Boolean.class,p.schema(),p.schemaOwner())))reasons.add("SCHEMA_OWNER_MISMATCH");
        facts.add(jdbc.queryForList("select nspname,nspowner,nspacl::text from pg_namespace where nspname=?",p.schema()));
        facts.add(jdbc.queryForList("select datname,datdba,datacl::text from pg_database where datname=current_database()"));
        facts.add(jdbc.queryForList("select a.roleid,a.member,a.admin_option,a.inherit_option,a.set_option from pg_auth_members a join pg_roles r on r.oid=a.member where r.rolname in (?,?,?) order by a.roleid,a.member",p.worker(),p.ops(),p.migration()));
        var acl=jdbc.queryForList("select c.relname,c.relacl::text,c.reloptions::text,case when c.relkind in ('v','m') then md5(pg_get_viewdef(c.oid,true)) end as view_definition_hash,pg_get_userbyid(c.relowner) as owner from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relkind in ('r','p','v','m','f') order by c.relname",p.schema());facts.add(acl);
        for(var r:acl){String table=p.schema()+"."+r.get("relname");
            for(String privilege:List.of("TRUNCATE","TRIGGER","REFERENCES"))for(String a:List.of(p.worker(),p.ops()))if(Boolean.TRUE.equals(jdbc.queryForObject(privilege.equals("REFERENCES")?"select has_any_column_privilege(?::name,?,?)":"select has_table_privilege(?::name,?,?)",Boolean.class,a,table,privilege)))reasons.add("UNSAFE_TABLE_PRIVILEGE:"+a+":"+privilege);
            if(Boolean.TRUE.equals(jdbc.queryForObject("select has_any_column_privilege(?::name,?,'SELECT')",Boolean.class,p.worker(),table))&&!Set.of("collection_archive_metadata","collection_assurance_metadata").contains(r.get("relname")))reasons.add("WORKER_UNFILTERED_READ:"+r.get("relname"));
            for(String privilege:List.of("INSERT","UPDATE","DELETE"))if(Boolean.TRUE.equals(jdbc.queryForObject(privilege.equals("DELETE")?"select has_table_privilege(?::name,?,?)":"select has_any_column_privilege(?::name,?,?)",Boolean.class,p.worker(),table,privilege)))reasons.add("DIRECT_WORKER_DML_GATEWAY_REQUIRED:"+r.get("relname"));
        }
        var columns=jdbc.queryForList("select c.relname,a.attname,a.atttypid,a.attnotnull,a.attacl::text from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relkind in ('r','p','v','m','f') and a.attnum>0 and not a.attisdropped order by c.relname,a.attnum",p.schema());facts.add(columns);
        for(var column:columns)if(Set.of("raw_body","body","payload","canonical_json","refresh_token_hash").contains(column.get("attname")))for(String role:List.of(p.worker(),p.ops()))if(Boolean.TRUE.equals(jdbc.queryForObject("select has_column_privilege(?::name,?,?, 'SELECT')",Boolean.class,role,p.schema()+"."+column.get("relname"),column.get("attname"))))reasons.add("SENSITIVE_COLUMN_READ:"+role+":"+column.get("relname"));
        var functions=jdbc.queryForList("select f.oid,f.prosecdef,f.proconfig::text,f.proacl::text,md5(pg_get_functiondef(f.oid)) as definition_hash from pg_proc f join pg_namespace n on n.oid=f.pronamespace where n.nspname=? and f.prokind='f' order by f.oid",p.schema());facts.add(functions);
        for(var function:functions)if(Boolean.TRUE.equals(function.get("prosecdef"))&&Boolean.TRUE.equals(jdbc.queryForObject("select has_function_privilege(?::name,?::oid,'EXECUTE')",Boolean.class,p.worker(),function.get("oid"))))reasons.add("UNREVIEWED_DEFINER_EXECUTION");
        return new Result(reasons.isEmpty(),List.copyOf(new LinkedHashSet<>(reasons)),CollectionAssurance.hash(CollectionAssurance.canonical(facts)));
    }
}
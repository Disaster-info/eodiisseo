package com.disasterinfo.eodiisseo.collection.operations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Startup attestation for a single isolated credential plane, not execution authorization. */
public final class CollectionLoginPlane {
    public enum Kind { WORKER, GATEWAY, OPS }
    private final Kind kind;
    private final String role;
    public CollectionLoginPlane(Kind kind,String role) {
        this.kind=Objects.requireNonNull(kind);this.role=Objects.requireNonNull(role);
        if(!role.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Exact LOGIN role required");
    }
    public Kind kind(){return kind;}
    public void verifyReview(JdbcTemplate jdbc,CollectionAssurance.Verifier verifier,CollectionLoginReviewPort reviews) {
        var a=reviews.current().orElseThrow(()->new IllegalStateException("Current signed LOGIN review required"));
        if(a.kind()!=CollectionAssurance.Kind.DATABASE || !"GLOBAL".equals(a.scope()) || !verifier.verify(a,CollectionOperationMode.PRODUCTION,jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant()))
            throw new IllegalStateException("Production DATABASE review signature required");
        var p=a.payload();
        var result=new CollectionDatabasePrivilegeInspector(jdbc).inspect(new CollectionDatabasePrivilegeInspector.Policy(
            (String)p.get("worker_role"),(String)p.get("ops_role"),(String)p.get("migration_role"),(String)p.get("schema_owner"),"public"));
        if(!result.safe() || !result.fingerprint().equals(p.get("acl_sha256")) || !"2026111301".equals(p.get("barrier_version")) ||
           !Objects.equals(jdbc.queryForObject("select collection_production.login_policy_hash(?,?::name,?::name)",String.class,kind.name(),p.get("worker_role"),p.get("ops_role")),p.get("gateway_sha256")))
            throw new IllegalStateException("Signed LOGIN policy no longer matches live privileges");
    }
    public void verify(JdbcTemplate jdbc,JpaTransactionManager manager) {
        var ds=Objects.requireNonNull(jdbc.getDataSource());
        if(!(ds instanceof CollectionLoginPool) || manager.getDataSource()!=ds ||
           !(manager.getEntityManagerFactory() instanceof EntityManagerFactoryInfo info) || info.getDataSource()!=ds)
            throw new IllegalStateException("One private LOGIN pool and shared JPA/JDBC transaction required");
        new TransactionTemplate(manager).execute(status->{
            var rows=jdbc.queryForList("""
                select r.rolcanlogin and not (r.rolsuper or r.rolinherit or r.rolcreatedb or r.rolcreaterole or r.rolreplication or r.rolbypassrls)
                and not exists(select 1 from pg_auth_members where member=r.oid) as safe,
                session_user::text as login,current_user::text as effective,
                has_database_privilege(current_user,current_database(),'CREATE,TEMP') as creates,
                has_schema_privilege(current_user,'public','CREATE') as public_create,
                has_schema_privilege(current_user,'collection_production','CREATE') as private_create,
                exists(select 1 from pg_class where relowner=r.oid and relnamespace in ('public'::regnamespace,'collection_production'::regnamespace)) as owns
                from pg_roles r where r.rolname=session_user
                """);
            if(rows.size()!=1)throw new IllegalStateException("LOGIN identity required");
            var r=rows.getFirst();
            if(!role.equals(r.get("login")) || !role.equals(r.get("effective")) || !Boolean.TRUE.equals(r.get("safe")) ||
               Boolean.TRUE.equals(r.get("creates")) || Boolean.TRUE.equals(r.get("public_create")) || Boolean.TRUE.equals(r.get("private_create")) || Boolean.TRUE.equals(r.get("owns")))
                throw new IllegalStateException("Runtime LOGIN privilege boundary rejected");
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select collection_production.verify_login_plane(?)",Boolean.class,kind.name())))
                throw new IllegalStateException("Reviewed exact LOGIN plane installation required");
            var em=EntityManagerFactoryUtils.getTransactionalEntityManager(manager.getEntityManagerFactory());
            if(em==null || ((Number)em.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue()!=jdbc.queryForObject("select pg_backend_pid()",Integer.class))
                throw new IllegalStateException("Physical JPA/JDBC transaction mismatch");
            return null;
        });
    }
}

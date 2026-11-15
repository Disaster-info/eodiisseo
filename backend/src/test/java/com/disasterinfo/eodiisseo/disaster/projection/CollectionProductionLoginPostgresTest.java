package com.disasterinfo.eodiisseo.disaster.projection;

import com.disasterinfo.eodiisseo.collection.operations.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same 4C-1 scenarios, now real SCRAM LOGINs and separate private Hikari pools.
 * Never SET ROLE / SET SESSION AUTHORIZATION to simulate a successful runtime login. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
class CollectionProductionLoginPostgresTest extends CollectionProductionGatewayPostgresTest {
    final Map<String,char[]> secrets=new HashMap<>();
    final Map<String,CollectionLoginPool> pools=new HashMap<>();
    String migration,migrationOwner;
    java.security.KeyPair reviewKey;
    final java.util.concurrent.atomic.AtomicReference<Optional<CollectionAssurance.Artifact>> currentReview=new java.util.concurrent.atomic.AtomicReference<>();
    @Override void createRole(String role){
        if(role.equals(definer)){super.createRole(role);return;}
        var bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        char[] secret=HexFormat.of().formatHex(bytes).toCharArray();Arrays.fill(bytes,(byte)0);secrets.put(role,secret);
        // Random test-only password. Never log query, exception or credential-bearing datasource.
        try(var c=ds.getConnection();var s=c.createStatement()){
            s.execute("create role "+role+" login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '"+new String(secret)+"'");
        }catch(SQLException failure){throw new IllegalStateException("Temporary LOGIN provisioning failed",null);}
        if(role.equals(legacy)){
            migrationOwner="c4c_"+UUID.randomUUID().toString().replace("-","")+"_m";createRole(migrationOwner);
            f.sql.execute("alter schema public owner to "+migrationOwner);
            // PostGIS defaults grant PUBLIC reads. Harden only this disposable database;
            // retain explicit Definer/Gateway grants and keep the existing strict inspector.
            f.sql.execute("revoke select on geography_columns,geometry_columns,spatial_ref_sys from public");
        }
    }
    @Override DataSource as(String role){
        return pools.computeIfAbsent(role,r->new CollectionLoginPool("jdbc:postgresql://127.0.0.1:55433/"+f.database,r,secrets.get(r),"audit-login-"+r,1));
    }
    @Override void closeConnections(){
        pools.values().forEach(CollectionLoginPool::close);pools.clear();
        secrets.values().forEach(p->Arrays.fill(p,'\0'));secrets.clear();
        if(migration!=null)f.sql.execute("drop role "+migration);
        // Schema ownership is confined to this database; drop the role after the parent drops it.

    }
    @Override void closeAdditionalRoles(){if(migrationOwner!=null)f.admin.execute("drop role "+migrationOwner);}
    void attestGrant(){
        for(String r:List.of(worker,gateway,operator)){
            f.sql.execute("grant execute on function collection_production.verify_login_plane(text),collection_production.login_policy_hash(text,name,name) to "+r);
        }
    }
    @Test void actualPasswordAuthenticationAndIndependentPhysicalSessions()throws Exception{
        var pids=new HashSet<Integer>();
        for(String r:List.of(worker,gateway,operator,legacy)){
            var sql=new JdbcTemplate(as(r));assertEquals(r,sql.queryForObject("select session_user::text",String.class));
            assertEquals(r,sql.queryForObject("select current_user::text",String.class));
            pids.add(sql.queryForObject("select pg_backend_pid()",Integer.class));
            var properties=new Properties();properties.setProperty("user",r);properties.setProperty("password","not-the-test-password");
            SQLException e=assertThrows(SQLException.class,()->DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55433/"+f.database,properties));
            assertEquals("28P01",e.getSQLState(),"Must test SCRAM, not a trust-authenticated connection");
        }
        assertEquals(4,pids.size());assertFalse(f.sql.queryForObject("select rolcanlogin from pg_roles where rolname=?",Boolean.class,definer));
    }
    @Test void workerCannotAcquireGatewayOpsMigrationOrDefinerRights()throws Exception{
        migration="c4c_"+UUID.randomUUID().toString().replace("-","")+"_m";createRole(migration);
        var workerSql=new JdbcTemplate(as(worker));
        for(String target:List.of(gateway,operator,definer,migration))assertThrows(RuntimeException.class,()->workerSql.execute("set role "+target));
        var migrateSql=new JdbcTemplate(as(migration));assertEquals(migration,migrateSql.queryForObject("select session_user::text",String.class));
        assertThrows(RuntimeException.class,()->migrateSql.queryForList("select collection_production.open_context(?,?,?,'CLAIM',null,null,null)",permit,capability,f.source));
        assertThrows(RuntimeException.class,()->gate.queryForList("select collection_production.freeze(?,?,?)",f.ops.memberId(),f.ops.sessionId(),"SYNTHETIC:DENIED"));
        assertThrows(RuntimeException.class,()->ops.queryForList("select collection_production.open_context(?,?,?,'CLAIM',null,null,null)",permit,capability,f.source));
        assertThrows(RuntimeException.class,()->workerSql.execute("alter role "+worker+" superuser"));
    }
    @Test void pooledSessionSettingsAndTransactionContextNeverLeak()throws Exception{
        ready();var claim=claim();var pool=(CollectionLoginPool)as(gateway);
        int pid;
        try(var c=pool.getConnection();var s=c.createStatement()){
            try(var r=s.executeQuery("select pg_backend_pid()")){r.next();pid=r.getInt(1);}
            s.execute("set eodiisseo.collection.worker='synthetic-stale'");
        }
        try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("select pg_backend_pid(),nullif(current_setting('eodiisseo.collection.worker',true),'') is null")){
            assertTrue(r.next());assertEquals(pid,r.getInt(1));assertTrue(r.getBoolean(2));
        }
        assertThrows(RuntimeException.class,()->gate.update("update collection_run set status='SUCCESS' where run_id=?",claim.run()));
        assertThrows(SQLException.class,()->pool.getConnection(operator,"forbidden"));
        assertThrows(SQLException.class,()->pool.unwrap(HikariDataSource.class));
        writes.write(handle(),f.source,ProductionCollectionWriteGateway.Phase.EXECUTION,claim,()->{ownership.heartbeat(claim);return null;});
    }
    @Test void loginPlaneAttestationRejectsWrongPoolAndRoleKind(){
        attestGrant();var manager=gatewayApp.getBean(JpaTransactionManager.class);
        var binding=new CollectionLoginPlane(CollectionLoginPlane.Kind.GATEWAY,gateway);binding.verify(gate,manager);
        assertThrows(IllegalStateException.class,()->new CollectionLoginPlane(CollectionLoginPlane.Kind.OPS,gateway).verify(gate,manager));
        assertThrows(IllegalStateException.class,()->new CollectionLoginPlane(CollectionLoginPlane.Kind.GATEWAY,worker).verify(gate,manager));
        assertThrows(IllegalStateException.class,()->binding.verify(new JdbcTemplate(as(worker)),manager));
        f.sql.execute("grant "+definer+" to "+gateway);
        try{assertThrows(IllegalStateException.class,()->binding.verify(gate,manager));}finally{f.sql.execute("revoke "+definer+" from "+gateway);}
    }

    CollectionReviewTrustRootsPort roots()throws Exception {
        reviewKey=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var key=reviewKey.getPublic();
        // Test-only trust key, no artifact is signed or approved by this test binding.
        return ()->Map.of("TEST_ONLY_NO_APPROVAL",new CollectionAssurance.TrustRoot(key,Set.of(CollectionAssurance.Kind.DATABASE),false));
    }
    CollectionAssurance.Artifact signedReview(CollectionLoginPlane.Kind kind,String role){
        return new org.springframework.transaction.support.TransactionTemplate(f.manager).execute(s->signedReviewInTransaction(kind,role));
    }
    CollectionAssurance.Artifact signedReviewInTransaction(CollectionLoginPlane.Kind kind,String role){
        f.sql.execute("set local TimeZone='UTC'");
        var policy=new CollectionDatabasePrivilegeInspector.Policy(worker,operator,migrationOwner,migrationOwner,"public");
        var result=new CollectionDatabasePrivilegeInspector(f.sql).inspect(policy);assertTrue(result.safe(),result.blockers().toString());
        String hash=f.sql.queryForObject("select encode(sha256(convert_to(collection_production.fingerprint()||':'||current_database()||':'||?||':'||?,'UTF8')),'hex')",String.class,kind.name(),role);
        var data=Map.<String,Object>of("worker_role",worker,"ops_role",operator,"migration_role",migrationOwner,"schema_owner",migrationOwner,"acl_sha256",result.fingerprint(),"barrier_version","2026111301","gateway_sha256",hash);
        var from=java.time.Instant.now().minusSeconds(5);var until=java.time.Instant.now().plusSeconds(60);
        var a=new CollectionAssurance.Artifact(UUID.randomUUID(),CollectionAssurance.Kind.DATABASE,"GLOBAL","TEST_ONLY","TEST_ONLY_NO_APPROVAL","TEST:LOGIN_REVIEW",data,from,until,false,new byte[0]);
        try{var sign=java.security.Signature.getInstance("Ed25519");sign.initSign(reviewKey.getPrivate());sign.update(a.signingBytes());
            return new CollectionAssurance.Artifact(a.id(),a.kind(),a.scope(),a.version(),a.issuer(),a.reference(),a.payload(),a.from(),a.until(),false,sign.sign());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    org.springframework.boot.test.context.runner.ApplicationContextRunner runner(CollectionLoginPlane.Kind kind,String role,
            org.springframework.context.annotation.AnnotationConfigApplicationContext owner,CollectionReviewTrustRootsPort trust) {
        var review=signedReview(kind,role);currentReview.set(Optional.of(review));
        return new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(CollectionLoginPlaneConfiguration.class)
            .withPropertyValues("eodiisseo.collection.login-plane.enabled=true","eodiisseo.collection.login-plane.kind="+kind.name())
            .withBean(CollectionLoginPlane.class,()->new CollectionLoginPlane(kind,role))
            .withBean("dataSource",DataSource.class,()->as(role),b->b.setDestroyMethodName(""))
            .withBean(jakarta.persistence.EntityManagerFactory.class,()->owner.getBean(jakarta.persistence.EntityManagerFactory.class),b->b.setDestroyMethodName(""))
            .withBean(JpaTransactionManager.class,()->owner.getBean(JpaTransactionManager.class))
            .withBean(JdbcTemplate.class,()->new JdbcTemplate(as(role)))
            .withBean(CollectionReviewTrustRootsPort.class,()->trust)
            .withBean(CollectionLoginReviewPort.class,()->currentReview::get);
    }
    @Test void springGatewayAssemblyUsesItsOwnLoginAndSharedPhysicalJpaJdbcTransaction()throws Exception{
        attestGrant();ready();
        runner(CollectionLoginPlane.Kind.GATEWAY,gateway,gatewayApp,roots()).run(c->{
            assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(ProductionCollectionCutover.class).isEmpty());
            var assembled=c.getBean(ProductionCollectionWriteGateway.class);
            var claim=assembled.write(handle(),f.source,ProductionCollectionWriteGateway.Phase.CLAIM,null,()->ownership.claim(f.source,"LIVE"));
            assertEquals("RUNNING",f.sql.queryForObject("select status from collection_run where run_id=?",String.class,claim.run()));
        });
    }
    @Test void springWorkerHasNoGatewayOrOpsAndBadAssemblyFailsStartup()throws Exception{
        attestGrant();try(var workerApp=context(as(worker))){
            var run=runner(CollectionLoginPlane.Kind.WORKER,worker,workerApp,roots());
            run.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(ProductionCollectionWriteGateway.class).isEmpty());assertTrue(c.getBeansOfType(CollectionOperationsService.class).isEmpty());});
            run.withBean("secondCredentialPlane",DataSource.class,()->as(operator),b->b.setDestroyMethodName("")).run(c->assertNotNull(c.getStartupFailure()));
            try(var privilegedParent=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
                privilegedParent.registerBean("opsCredentialPlane",DataSource.class,()->as(operator),b->b.setDestroyMethodName(""));privilegedParent.refresh();
                run.withParent(privilegedParent).run(c->assertNotNull(c.getStartupFailure()));
            }
            runner(CollectionLoginPlane.Kind.GATEWAY,worker,workerApp,roots()).run(c->assertNotNull(c.getStartupFailure()));
            runner(CollectionLoginPlane.Kind.WORKER,worker,workerApp,()->Map.of()).run(c->assertNotNull(c.getStartupFailure()));
        }
    }
    @Test void springOpsUsesExistingActiveSessionGuardsWithoutAuthDmlPrivileges()throws Exception{
        attestGrant();
        f.sql.execute("grant execute on function collection_production.authorize_ops(uuid,uuid) to "+operator);
        f.sql.execute("grant select on flyway_schema_history to "+operator);
        f.sql.execute("grant select(member_id,status) on member to "+operator);
        f.sql.execute("grant select(session_id,member_id,expires_at,revoked_at) on auth_session to "+operator);
        var trust=roots();var verifier=new CollectionAssurance.Verifier(trust.roots());
        try(var operatorApp=context(as(operator))){
            var manager=operatorApp.getBean(JpaTransactionManager.class);
            var ref=new java.util.concurrent.atomic.AtomicReference<CollectionOperationsService>();
            var assurance=new JdbcCollectionAssurance(ops,manager,ref::get,CollectionOperationMode.PRODUCTION,verifier);
            var evidence=new VerifiedCollectionOperationalEvidence(assurance,()->Optional.empty(),null,Set.of(),java.time.Clock.systemUTC());
            runner(CollectionLoginPlane.Kind.OPS,operator,operatorApp,trust)
                .withBean(JdbcCollectionAssurance.class,()->assurance)
                .withBean(VerifiedCollectionOperationalEvidence.class,()->evidence)
                .run(c->{
                    assertNull(c.getStartupFailure());ref.set(c.getBean(CollectionOperationsService.class));
                    var control=c.getBean(ProductionCollectionCutover.class);
                    var user=f.member(false);assertThrows(RuntimeException.class,()->control.freeze(user,"SYNTHETIC:DENIED"));
                    control.freeze(f.ops,"SYNTHETIC:OPS_LOGIN_FREEZE");
                    assertEquals("FROZEN",f.sql.queryForObject("select state from collection_production.barrier",String.class));
                    assertThrows(RuntimeException.class,()->ops.update("update auth_session set revoked_at=now()"));
                    assertThrows(RuntimeException.class,()->ops.update("update member set status='WITHDRAWN'"));
                    assertThrows(RuntimeException.class,()->ops.queryForList("select refresh_token_hash from auth_session"));
                    f.sql.update("update auth_session set revoked_at=now() where session_id=?",f.ops.sessionId());
                    assertThrows(RuntimeException.class,()->control.freeze(f.ops,"SYNTHETIC:REVOKED"));
                });
        }
    }

    @Test void migrationLoginAppliesAndValidatesFreshDatabaseWithoutRuntimeRoleMembership()throws Exception{
        String role="c4c_"+UUID.randomUUID().toString().replace("-","")+"_m";
        String database="collection4c_"+UUID.randomUUID().toString().replace("-","");
        createRole(role);boolean created=false;
        try {
            f.admin.execute("create database "+database+" owner "+role);created=true;
            var bootstrap=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55433/"+database,"eodiisseo_audit",""));
            // Untrusted PostGIS extension requires the isolated bootstrap superuser. Migration LOGIN does not.
            bootstrap.execute("create extension postgis");
            try(var pool=new CollectionLoginPool("jdbc:postgresql://127.0.0.1:55433/"+database,role,secrets.get(role),"audit-migration",2)){
                var flyway=org.flywaydb.core.Flyway.configure().dataSource(pool).locations(locations()).load();
                assertTrue(flyway.migrate().migrationsExecuted>0);flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
                var sql=new JdbcTemplate(pool);assertEquals(role,sql.queryForObject("select session_user::text",String.class));
                assertEquals(1,sql.queryForObject("select count(*) from flyway_schema_history where version='2026111302' and success",Integer.class));
                for(String runtime:List.of(worker,gateway,operator))assertFalse(sql.queryForObject("select pg_has_role(?,?,'MEMBER')",Boolean.class,runtime,role));
            }
        } finally {
            if(created)f.admin.execute("drop database "+database);
            f.admin.execute("drop role "+role);Arrays.fill(secrets.remove(role),'\0');
        }
    }

    @Test void signedReviewRevocationDuringGatewayWorkRollsBackAndMissingSignatureRefusesStartup()throws Exception{
        attestGrant();ready();var run=runner(CollectionLoginPlane.Kind.GATEWAY,gateway,gatewayApp,roots());
        run.run(c->{
            assertNull(c.getStartupFailure());var gateway=c.getBean(ProductionCollectionWriteGateway.class);
            assertThrows(IllegalStateException.class,()->gateway.write(handle(),f.source,ProductionCollectionWriteGateway.Phase.CLAIM,null,()->{
                ownership.claim(f.source,"LIVE");currentReview.set(Optional.empty());return null;
            }));assertEquals(0,count("collection_run"));assertEquals(0,count("collection_target_lease"));
        });
        run=runner(CollectionLoginPlane.Kind.GATEWAY,gateway,gatewayApp,roots());currentReview.set(Optional.empty());
        run.run(c->assertNotNull(c.getStartupFailure()));
        run=runner(CollectionLoginPlane.Kind.GATEWAY,gateway,gatewayApp,roots());var original=currentReview.get().orElseThrow();
        currentReview.set(Optional.of(new CollectionAssurance.Artifact(original.id(),original.kind(),original.scope(),original.version(),original.issuer(),original.reference(),original.payload(),original.from(),original.until(),false,new byte[64])));
        run.run(c->assertNotNull(c.getStartupFailure()));
    }

    @Test void sessionExpiryAfterExplicitCloseStillRejectsTheActualCommit(){
        ready();var expires=f.sql.queryForObject("select clock_timestamp()+interval '20 seconds'",Timestamp.class);
        f.sql.update("update auth_session set created_at=?::timestamptz-interval '336 hours',expires_at=? where session_id=?",expires,expires,f.ops.sessionId());
        var tx=new org.springframework.transaction.support.TransactionTemplate(gatewayApp.getBean(JpaTransactionManager.class));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        var closedBeforeExpiry=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(RuntimeException.class,()->tx.execute(status->{
            gate.queryForList("select collection_production.open_context(?,?,?,'CLAIM',null,null,null)",permit,capability,f.source);
            ownership.claim(f.source,"LIVE");gate.queryForList("select collection_production.close_context()");
            assertTrue(gate.queryForObject("select clock_timestamp() < ?::timestamptz",Boolean.class,expires));closedBeforeExpiry.set(true);
            // Use the DB clock and actual COMMIT, not a guessed fixed client sleep.
            gate.queryForList("select pg_sleep(greatest(0.0,extract(epoch from (?::timestamptz-clock_timestamp())))+0.1)",expires);return null;
        }));
        assertTrue(closedBeforeExpiry.get(),"Must reach explicit close before testing deferred COMMIT expiry");
        assertEquals(0,count("collection_run"));assertEquals(0,count("collection_target_lease"));assertEquals(0,count("collection_job_lock"));assertEquals(0,count("collection_production.context"));
    }
    @Test void cutoverRejectsEvidenceFromADifferentCredentialPlane()throws Exception{
        f.sql.execute("grant select on flyway_schema_history to "+operator);
        try(var app=context(as(operator))){
            var manager=app.getBean(JpaTransactionManager.class);var reference=new java.util.concurrent.atomic.AtomicReference<CollectionOperationsService>();
            var verifier=new CollectionAssurance.Verifier(roots().roots());
            var assurance=new JdbcCollectionAssurance(ops,manager,reference::get,CollectionOperationMode.PRODUCTION,verifier);
            var other=new JdbcCollectionAssurance(gate,gatewayApp.getBean(JpaTransactionManager.class),reference::get,CollectionOperationMode.PRODUCTION,verifier);
            var wrong=new VerifiedCollectionOperationalEvidence(other,()->Optional.empty(),null,Set.of(),java.time.Clock.systemUTC());
            var service=CollectionOperationsService.production(ops,manager,wrong,()->{});reference.set(service);
            assertThrows(IllegalArgumentException.class,()->new ProductionCollectionCutover(ops,service,assurance,wrong));
        }
    }
}

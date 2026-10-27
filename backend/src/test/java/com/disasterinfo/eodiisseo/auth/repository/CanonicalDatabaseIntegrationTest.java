package com.disasterinfo.eodiisseo.auth.repository;

import com.disasterinfo.eodiisseo.auth.client.SocialIdentityVerifier;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import com.disasterinfo.eodiisseo.checklist.repository.ChecklistRepository;
import com.disasterinfo.eodiisseo.checklist.service.ChecklistService;
import com.disasterinfo.eodiisseo.group.repository.GroupRepository;
import com.disasterinfo.eodiisseo.group.service.GroupService;
import com.disasterinfo.eodiisseo.ops.repository.OpsRepository;
import com.disasterinfo.eodiisseo.ops.service.OpsService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real production migrations, on a newly created disposable database. No production fixtures. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
class CanonicalDatabaseIntegrationTest {
    static String name;static JdbcTemplate admin;static OwnedDatabase db;
    static DriverManagerDataSource source;static DataSourceTransactionManager manager;static TransactionTemplate tx;
    static Clock clock=Clock.systemUTC();
    static Flyway flyway;
    @BeforeAll static void createDatabase() {
        String url=System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")) || url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55433/postgres"))
            throw new IllegalStateException("Use only the isolated local audit cluster on 55433");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"eodiisseo_audit",""));
        name="eodiisseo_verify_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+name);
        source=new DriverManagerDataSource(url.replace("/postgres","/"+name),"eodiisseo_audit","");
        db=new OwnedDatabase(new JdbcTemplate(source));manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        flyway.migrate();flyway.validate();
    }
    @AfterAll static void dropOnlyCreatedDatabase() {
        if(admin!=null && name!=null && name.matches("eodiisseo_verify_[0-9a-f]{32}")) admin.execute("DROP DATABASE "+name+" WITH (FORCE)");
    }
    static UUID member(){UUID id=UUID.randomUUID();db.update("insert into member values(?,'integration member','ACTIVE',?,null)",id,clock.instant());return id;}
    static AuthService auth(){return new AuthService(new AuthRepository(db),new TokenService("test-only-key-0123456789abcdefghijk","test","test",900,clock),mock(SocialIdentityVerifier.class),clock,manager,1209600);}
    @Test void canonicalSchemaAndMigrationsAreRepeatable(){
        assertEquals(0,flyway.migrate().migrationsExecuted);
        assertEquals(37,db.count("select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name not in ('spatial_ref_sys','flyway_schema_history')"));
        assertEquals(3,db.count("select count(*) from checklist_template where active=true"));
        assertNotNull(db.one("select postgis_full_version() as version").get("version"));
    }
    @Test void sessionExpiryAndForeignKeysAreEnforced(){
        UUID member=member();Instant now=clock.instant();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("insert into auth_session values(?,?,?,?,?,null)",UUID.randomUUID(),member,"a".repeat(64),now,now.plusSeconds(60)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("insert into member_role values(?,'OPS',?)",UUID.randomUUID(),now));
    }
    @Test void refreshReplayRevokesTheWholeSessionAndLogoutIsVisible(){
        UUID member=member(),session=UUID.randomUUID();Instant now=clock.instant();String old="test-only-"+UUID.randomUUID();
        db.update("insert into auth_session values(?,?,?,?,?,null)",session,member,AuthService.hash(old),now,now.plus(Duration.ofDays(14)));
        var auth=auth();var rotated=auth.refresh(old);assertNotEquals(old,rotated.get("refreshToken"));
        assertEquals(401,assertThrows(ApiProblem.class,()->auth.refresh(old)).status());
        assertNull(new AuthRepository(db).findUsableSession(session,member));
        assertThrows(ApiProblem.class,()->auth.refresh((String)rotated.get("refreshToken")));
    }
    @Test void publishedChecklistCompletionIsIdempotentAndPrivate(){
        var service=new ChecklistService(new ChecklistRepository(db),clock);UUID actor=member(),other=member();
        UUID template=UUID.fromString("40000000-0000-0000-0000-000000000001"),item=UUID.fromString("50000000-0000-0000-0000-000000000001");
        assertEquals(3,service.get(actor,"EARTHQUAKE").get("totalCount"));
        tx.execute(s->service.complete(actor,template,item,true));tx.execute(s->service.complete(actor,template,item,true));
        assertEquals(1L,service.get(actor,"EARTHQUAKE").get("completedCount"));
        assertEquals(0L,service.get(other,"EARTHQUAKE").get("completedCount"));
        tx.execute(s->service.complete(actor,template,item,false));assertEquals(0L,service.get(actor,"EARTHQUAKE").get("completedCount"));
    }
    @Test void concurrentJoinsRespectMemberLimit() throws Exception {
        var service=new GroupService(new GroupRepository(db),clock,300);UUID actor=member(),owner=member();
        tx.execute(s->service.create(actor,"one",null));tx.execute(s->service.create(actor,"two",null));
        var groups=List.of(tx.execute(s->service.create(owner,"candidate one",null)),tx.execute(s->service.create(owner,"candidate two",null)));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var jobs=new ArrayList<Future<Boolean>>();
            for(var group:groups) jobs.add(pool.submit(()->{ready.countDown();start.await();try{tx.execute(s->service.join(actor,(String)group.get("inviteCode")));return true;}catch(ApiProblem failure){assertEquals("GROUP_LIMIT_EXCEEDED",failure.code());return false;}}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();int accepted=0;for(var result:jobs)if(result.get(10,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(3,db.count("select count(*) from group_member where member_id=?",actor));
        }
    }
    @Test @SuppressWarnings("unchecked") void officialBoundaryEdgeAndConsentRevocationProtectLocations(){
        UUID actor=member();var service=new GroupService(new GroupRepository(db),clock,300);var group=tx.execute(s->service.create(actor,"spatial",null));
        String area="test-"+UUID.randomUUID().toString().substring(0,8);UUID disaster=UUID.randomUUID(),relation=UUID.randomUUID();Instant now=clock.instant();
        db.update("insert into admin_area(area_code,name,level,active,boundary) values(?,'fixture','SIGUNGU',true,ST_GeomFromText('MULTIPOLYGON(((126 37,127 37,127 38,126 38,126 37)))',4326))",area);
        db.update("insert into disaster(disaster_id,disaster_type_id,title,occurred_at,status,data_origin,updated_at) values(?,'10000000-0000-0000-0000-000000000001','test-only',?,'ONGOING','OFFICIAL',?)",disaster,now,now);
        UUID source=UUID.randomUUID(),record=UUID.randomUUID();
        db.update("insert into data_source(source_id,name,source_kind,endpoint_key,active) values(?,'test-only','DISASTER',?,true)",source,"test-"+source);
        db.update("insert into source_disaster(source_disaster_id,disaster_id,source_id,source_record_key,original_text,collected_at,content_hash) values(?,?,?,'fixture','test-only',?,?)",record,disaster,source,now,"a".repeat(64));
        db.update("insert into disaster_area(disaster_area_id,disaster_id,area_code,area_role,basis,source_disaster_id,derived_at) values(?,?,?,'IMPACT','OFFICIAL',?,?)",relation,disaster,area,record,now);
        db.update("insert into member_location_consent values(?,true,?)",actor,now);
        db.update("insert into member_location values(?,'GPS',ST_SetSRID(ST_MakePoint(126,37.5),4326)::geography,?)",actor,now);
        db.update("update group_member set location_sharing_enabled=true where member_id=?",actor);
        var members=(List<Map<String,Object>>)tx.execute(s->service.impacts(actor,(UUID)group.get("groupId"))).get("members");
        assertEquals("INSIDE_AFFECTED_AREA",members.getFirst().get("status"));
        db.update("update member_location_consent set consented=false where member_id=?",actor);
        members=(List<Map<String,Object>>)service.locations(actor,(UUID)group.get("groupId")).get("members");
        assertNull(members.getFirst().get("latitude"));assertNull(members.getFirst().get("longitude"));
    }
    @Test @SuppressWarnings("unchecked") void operationsQueriesRunAgainstCanonicalSchema(){
        var service=new OpsService(new OpsRepository(db),new MockEnvironment(),mock(ObjectProvider.class));
        assertNotNull(service.status("LIVE").get("apiStatuses"));assertNotNull(service.disasters(null,null,null,null,0,20,"LIVE").get("disasters"));
        assertNotNull(service.errors(null,null,null,0,20,"LIVE").get("errorLogs"));
        assertEquals(503,assertThrows(ApiProblem.class,()->service.refresh(member(),null,"LIVE")).status());
    }
}

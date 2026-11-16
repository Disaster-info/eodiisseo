package com.disasterinfo.eodiisseo.auth.repository;
import com.disasterinfo.eodiisseo.auth.client.SocialIdentityVerifier;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.checklist.repository.ChecklistRepository;
import com.disasterinfo.eodiisseo.group.repository.GroupRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.disasterinfo.eodiisseo.auth.service.*;
import com.disasterinfo.eodiisseo.group.service.GroupService;
import com.disasterinfo.eodiisseo.checklist.service.ChecklistService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker=true)
class AssignedDatabaseIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
    static OwnedDatabase db;static DataSourceTransactionManager manager;static TransactionTemplate tx;
    static final Instant NOW=Instant.parse("2026-10-16T03:00:00Z");static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    @BeforeAll static void prepareSchema() {
        DataSource ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("auth/shared-schema-fixture.sql")).execute(ds);
        // Baseline is safe here only because this is an isolated, newly-created test container.
        Flyway.configure().dataSource(ds).baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/assigned").load().migrate();
        db=new OwnedDatabase(new JdbcTemplate(ds));manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
    }
    static UUID member() { var id=UUID.randomUUID();db.update("insert into member(member_id,display_name,status,created_at) values(?,'test-only member','ACTIVE',?)",id,NOW);return id; }
    @Test void reusedRefreshReallyRevokesTheCommittedSession() {
        var member=member();var session=UUID.randomUUID();var old="test-only-old-token";
        db.update("insert into auth_session(session_id,member_id,refresh_token_hash,created_at,expires_at) values(?,?,?,?,?)",session,member,AuthService.hash(old),NOW,NOW.plusSeconds(600));
        var tokens=new TokenService("0123456789abcdef0123456789abcdef","test","test",900,CLOCK);
        var service=new AuthService(new AuthRepository(db),tokens,mock(SocialIdentityVerifier.class),CLOCK,manager,1209600);
        var rotated=service.refresh(old);assertNotEquals(old,rotated.get("refreshToken"));
        assertEquals(401,assertThrows(ApiProblem.class,()->service.refresh(old)).status());
        assertNotNull(db.one("select revoked_at from auth_session where session_id=?",session).get("revoked_at"));
        assertThrows(ApiProblem.class,()->service.refresh((String)rotated.get("refreshToken")));
    }
    @Test void concurrentJoinsCannotCreateAFourthMembership() throws Exception {
        var service=new GroupService(new GroupRepository(db),CLOCK,300);var actor=member();var owner=member();
        tx.execute(s->service.create(actor,"one",null));tx.execute(s->service.create(actor,"two",null));
        var first=tx.execute(s->service.create(owner,"candidate one",null));var second=tx.execute(s->service.create(owner,"candidate two",null));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<Future<Boolean>>();
            for(var candidate:List.of(first,second)) jobs.add(pool.submit(()->{
                ready.countDown();start.await();
                try { tx.execute(s->service.join(actor,(String)candidate.get("inviteCode")));return true; }
                catch(ApiProblem p) { assertEquals("GROUP_LIMIT_EXCEEDED",p.code());return false; }
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            long accepted=0;for(var result:jobs) if(result.get(10,TimeUnit.SECONDS)) accepted++;
            assertEquals(1,accepted);assertEquals(3,db.count("select count(*) from group_member where member_id=?",actor));
        }
    }
    @Test @SuppressWarnings("unchecked") void officialBoundaryEdgeCountsInsideWithoutUsingOccurrenceAsImpact() {
        var actor=member();var service=new GroupService(new GroupRepository(db),CLOCK,300);var group=tx.execute(s->service.create(actor,"spatial",null));
        db.update("insert into member_location_consent values(?,true,?)",actor,NOW);
        db.update("insert into member_location values(?,'GPS',ST_SetSRID(ST_MakePoint(126,37.5),4326)::geography,?)",actor,NOW);
        db.update("update group_member set location_sharing_enabled=true where member_id=?",actor);
        var area="test-"+UUID.randomUUID().toString().substring(0,8);var disaster=UUID.randomUUID();
        db.update("insert into admin_area(area_code,boundary) values(?,ST_GeomFromText('MULTIPOLYGON(((126 37,127 37,127 38,126 38,126 37)))',4326))",area);
        db.update("insert into disaster(disaster_id,status,data_origin) values(?,'ONGOING','OFFICIAL')",disaster);
        db.update("insert into disaster_area values(?,?,?,'OCCURRENCE','OFFICIAL')",UUID.randomUUID(),disaster,area);
        var result=(List<Map<String,Object>>)tx.execute(s->service.impacts(actor,(UUID)group.get("groupId"))).get("members");
        assertEquals("AFFECTED_AREA_UNKNOWN",result.getFirst().get("status"));
        db.update("insert into disaster_area values(?,?,?,'IMPACT','OFFICIAL')",UUID.randomUUID(),disaster,area);
        result=(List<Map<String,Object>>)tx.execute(s->service.impacts(actor,(UUID)group.get("groupId"))).get("members");
        assertEquals("INSIDE_AFFECTED_AREA",result.getFirst().get("status"));
        assertEquals(NOW,result.getFirst().get("locationUpdatedAt"));
    }
    @Test void completionUpsertIsIdempotentAndDoesNotChangeAnotherMembersProgress() {
        var type=UUID.randomUUID();var resource=UUID.randomUUID();var template=UUID.randomUUID();var item=UUID.randomUUID();var actor=member();var other=member();
        db.update("insert into disaster_type(disaster_type_id,code) values(?,'TEST_CHECKLIST')",type);
        db.update("insert into official_resource(resource_id,agency_name,title,source_url,checked_at) values(?,'test-only','test-only','https://example.invalid/',?)",resource,NOW);
        db.update("insert into checklist_template values(?,?,?,'test-only',1,true,?)",template,type,resource,NOW);
        db.update("insert into checklist_item values(?,?,1,'test-only',true)",item,template);
        var service=new ChecklistService(new ChecklistRepository(db),CLOCK);
        tx.execute(s->service.complete(actor,template,item,true));tx.execute(s->service.complete(actor,template,item,true));
        assertEquals(1,db.count("select count(*) from member_check_item where member_id=?",actor));
        assertEquals(0,db.count("select count(*) from member_check_item where member_id=?",other));
        tx.execute(s->service.complete(actor,template,item,false));assertEquals(0,db.count("select count(*) from member_check_item where member_id=?",actor));
    }
}

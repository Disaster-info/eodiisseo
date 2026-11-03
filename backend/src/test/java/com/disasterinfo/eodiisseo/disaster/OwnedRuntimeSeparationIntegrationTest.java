package com.disasterinfo.eodiisseo.disaster;

import com.disasterinfo.eodiisseo.EodiisseoApplication;
import com.disasterinfo.eodiisseo.auth.client.SocialIdentityVerifier;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import com.disasterinfo.eodiisseo.auth.security.*;
import com.disasterinfo.eodiisseo.dashboard.port.*;
import com.disasterinfo.eodiisseo.ai.summary.*;
import com.disasterinfo.eodiisseo.ai.summary.port.*;
import com.disasterinfo.eodiisseo.ai.chat.*;
import com.disasterinfo.eodiisseo.ai.chat.dto.*;
import com.disasterinfo.eodiisseo.ai.chat.adapter.ExternalAiConsentService;
import com.disasterinfo.eodiisseo.ai.chat.port.*;
import com.disasterinfo.eodiisseo.ai.resource.adapter.*;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.disaster.service.*;
import com.disasterinfo.eodiisseo.notification.*;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real target-branch auth/DB/HTTP. Missing owner integrations are asserted as missing, never faked as production. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(120)
class OwnedRuntimeSeparationIntegrationTest {
    private ConfigurableApplicationContext app;
    private JdbcTemplate admin,sql;private TransactionTemplate tx;
    private Path key;private String name,url;
    private static final UUID SOURCE=UUID.fromString("20000000-0000-0000-0000-000000000003");
    @BeforeAll void start() throws Exception {
        String base=System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE")) || !"jdbc:postgresql://127.0.0.1:55433/postgres".equals(base))
            throw new IllegalStateException("An explicitly disposable audit cluster is required");
        name="owned_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,"eodiisseo_audit",""));
        admin.execute("CREATE DATABASE "+name);url=base.substring(0,base.length()-8)+name;
        key=Files.createTempFile("owned-test-only-",".key");
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        Files.writeString(key,Base64.getEncoder().encodeToString(bytes));Arrays.fill(bytes,(byte)0);
        open();
        // Synthetic registry fixture only. No collector or reference-data migration is imported from another branch.
        sql.update("insert into data_source(source_id,name,source_kind,endpoint_key,collection_interval_minutes,active) values(?,'TEST EARTHQUAKE SOURCE','DISASTER','KMA_EARTHQUAKE',5,true)",SOURCE);
    }
    private void open() {
        app=new SpringApplicationBuilder(EodiisseoApplication.class,Providers.class).web(WebApplicationType.SERVLET)
            .run("--spring.profiles.active=owned-runtime-integration","--server.address=127.0.0.1","--server.port=0",
                "--spring.datasource.url="+url,"--spring.datasource.username=eodiisseo_audit","--spring.datasource.password=",
                "--EODIISSEO_NOTIFICATION_TOKEN_KEY_PATH="+key,"--JWT_SECRET=owned-separation-test-only-0123456789abcdef",
                "--eodiisseo.ai.external-processing.enabled=true","--logging.level.root=WARN");
        sql=app.getBean(JdbcTemplate.class);tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
    }
    @AfterAll void stop() throws Exception {
        if(app!=null)app.close();
        if(admin!=null && name!=null && name.matches("owned_[a-f0-9]{32}"))admin.execute("DROP DATABASE "+name+" WITH (FORCE)");
        if(key!=null)Files.deleteIfExists(key);
    }
    private MemberPrincipal actor() {
        return principal(app.getBean(AuthService.class).login("GOOGLE","fixture-"+UUID.randomUUID()).body());
    }
    private MemberPrincipal principal(Map<String,Object> response) {
        return app.getBean(TokenService.class).verify((String)response.get("accessToken"));
    }
    private HttpResponse<String> http(MemberPrincipal actor,String method,String path,Object body) throws Exception {
        int port=((ServletWebServerApplicationContext)app).getWebServer().getPort();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(actor!=null)request.header("Authorization","Bearer "+app.getBean(TokenService.class).issue(actor.memberId(),actor.sessionId(),Instant.now().plusSeconds(600)));
        var data=body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body));
        try(var client=HttpClient.newHttpClient()){return client.send(request.method(method,data).build(),HttpResponse.BodyHandlers.ofString());}
    }
    private UUID write(String key,String tag) {
        return tx.execute(status->{
            var payload=Map.<String,Object>of("tmEqk","20261030090000","lat","37.1","lon","127.1","loc","TEST FIXTURE "+tag);
            app.getBean(DisasterService.class).earthquake(SOURCE,key,payload,"TEST ORIGINAL "+tag,Instant.now());
            sql.update("insert into collection_run(run_id,source_id,mode,started_at,finished_at,status,trigger_type) values(?,?,'LIVE',?,?,'SUCCESS','MANUAL')",
                UUID.randomUUID(),SOURCE,Timestamp.from(Instant.now()),Timestamp.from(Instant.now()));
            return sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key=?",UUID.class,SOURCE,key);
        });
    }
    private UUID revision(UUID disaster) {return sql.queryForObject("select r.revision_id from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=? order by revision_no desc limit 1",UUID.class,disaster);}
    private void drain() {
        var worker=app.getBean(DisasterRuntimeWorker.class);
        for(int i=0;i<20 && worker.drainOneEvent();i++){}
        for(int i=0;i<20 && worker.drainOneSummary();i++){}
    }

    @Test @Order(1) void protectedOwnerBoundariesRemainDisconnectedAndPortsAreUnique() throws Exception {
        assertThat(app.getEnvironment().getProperty("AUTH_BRIDGES_ENABLED")).isEqualTo("true");
        assertThat(app.getBeansOfType(DashboardDisasterQueryReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(DashboardDisasterReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(SummarySourceReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(SeverityReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(DisasterReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(Authentication.class)).hasSize(1);
        assertThat(app.getBeansOfType(InterestAreas.class)).isEmpty();
        assertThat(app.getBeansOfType(DashboardLocationReader.class)).isEmpty();
        var member=actor();
        assertThat(sql.queryForObject("select count(*) from notification_setting where member_id=?",Long.class,member.memberId())).isZero();
        assertThat(http(member,"GET","/api/v1/notification-settings",null).statusCode()).isEqualTo(500);
        assertThat(http(member,"GET","/api/v1/dashboard?latitude=37&longitude=127",null).statusCode()).isEqualTo(500);
        // An explicit owned lifecycle call is possible; this does not claim automatic signup integration.
        app.getBean(NotificationRetentionService.class).onMemberCreated(member.memberId(),Instant.now());
        assertThat(sql.queryForObject("select enabled from notification_setting where member_id=?",Boolean.class,member.memberId())).isFalse();
        assertThat(http(member,"GET","/api/v1/notification-settings",null).statusCode()).isEqualTo(500); // Area owner Port still missing.
        UUID[] rolled={null};
        tx.executeWithoutResult(status->{
            var nested=actor();rolled[0]=nested.memberId();
            app.getBean(NotificationRetentionService.class).onMemberCreated(nested.memberId(),Instant.now());
            status.setRollbackOnly();
        });
        assertThat(sql.queryForObject("select count(*) from member where member_id=?",Long.class,rolled[0])).isZero();
        assertThat(sql.queryForObject("select count(*) from notification_setting where member_id=?",Long.class,rolled[0])).isZero();
        assertThat(sql.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name not in ('spatial_ref_sys','flyway_schema_history')",Long.class)).isEqualTo(42);
    }

    @Test @Order(2) void ownedReadsRevisionOutboxAndSummaryWorkWithoutBorrowingACollector() throws Exception {
        var member=actor();
        assertThat(http(null,"GET","/api/v1/disasters",null).statusCode()).isEqualTo(401);
        assertThat(http(member,"GET","/api/v1/disasters",null).body()).contains("\"disasters\":[]");
        String key="owned-"+UUID.randomUUID();UUID disaster=write(key,"first");
        UUID first=revision(disaster);
        write(key,"first");
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision where source_disaster_id in (select source_disaster_id from source_disaster where disaster_id=?)",Long.class,disaster)).isEqualTo(1);
        assertThat(http(member,"GET","/api/v1/dashboard",null).statusCode()).isEqualTo(200);
        assertThat(http(member,"GET","/api/v1/disasters/"+disaster,null).body()).contains("TEST ORIGINAL first","LATEST");
        assertThat(http(member,"GET","/api/v1/disasters/"+UUID.randomUUID(),null).statusCode()).isEqualTo(404);
        assertThat(http(member,"GET","/api/v1/disasters?page=-1",null).statusCode()).isEqualTo(400);
        drain();
        assertThat(http(member,"GET","/api/v1/disasters/"+disaster+"/ai-summary",null).body()).contains("COMPLETED");
        // Notification dispatch is held because the real InterestAreas implementation does not exist on this branch.
        assertThat(sql.queryForObject("select state from disaster_runtime_outbox where disaster_id=? and event_kind='NEW'",String.class,disaster)).isEqualTo("HELD");
        assertThat(sql.queryForObject("select last_error_code from disaster_runtime_outbox where disaster_id=? and event_kind='NEW'",String.class,disaster)).isEqualTo("NOTIFICATION_UNAVAILABLE");
        write(key,"second");
        assertThat(http(member,"GET","/api/v1/disasters/"+disaster+"/ai-summary",null).body()).contains("STALE");
        drain();
        assertThat(sql.queryForObject("select status from ai_summary where source_revision_id=?",String.class,first)).isEqualTo("STALE");
        assertThat(app.getBean(SummarySourceReader.class).isCurrentRevision(first)).isFalse();
        UUID[] rolled={null};
        tx.executeWithoutResult(status->{rolled[0]=write("rollback-"+UUID.randomUUID(),"rollback");status.setRollbackOnly();});
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=?",Long.class,rolled[0])).isZero();
    }

    @Test @Order(3) void sessionBoundConsentDoesNotRestoreAfterUnmodifiedAuthWithdrawal() throws Exception {
        String credential="consent-"+UUID.randomUUID();
        var auth=app.getBean(AuthService.class);var member=principal(auth.login("GOOGLE",credential).body());
        var consent=app.getBean(ExternalAiConsentService.class);
        assertThat(consent.permits(member.memberId())).isFalse();
        sql.update("insert into ai_external_consent(member_id,granted,changed_at) values(?,true,?)",member.memberId(),Timestamp.from(Instant.now()));
        assertThat(consent.permits(member.memberId())).isFalse(); // Legacy grant without a verified granting session is not restored.
        consent.record(member.memberId(),member.sessionId(),true);
        assertThat(consent.permits(member.memberId())).isTrue();
        auth.withdraw(member);
        assertThat(consent.permits(member.memberId())).isFalse();
        var restored=principal(auth.restore("GOOGLE",credential));
        assertThat(restored.memberId()).isEqualTo(member.memberId());
        assertThat(consent.permits(member.memberId())).isFalse();
        consent.record(restored.memberId(),restored.sessionId(),true);
        assertThat(consent.permits(restored.memberId())).isTrue();
        auth.logout(restored);
        assertThat(consent.permits(restored.memberId())).isFalse();
        var fresh=principal(auth.login("GOOGLE",credential).body());
        consent.record(fresh.memberId(),fresh.sessionId(),true);auth.withdraw(fresh);
        sql.update("update member set withdrawn_at=? where member_id=?",Timestamp.from(Instant.now().minus(Duration.ofDays(8))),fresh.memberId());
        auth.purgeExpiredMembers();
        assertThat(sql.queryForObject("select count(*) from ai_external_consent where member_id=?",Long.class,fresh.memberId())).isZero();
    }

    @Test @Order(4) void encryptedDevicesAndReviewedChatRetainRealSecurity() throws Exception {
        var member=actor();var other=actor();
        var registered=http(member,"PUT","/api/v1/notification-devices/owned-test-install",Map.of("fcmToken","test-only-owned-token","platform","ANDROID"));
        assertThat(registered.statusCode()).isEqualTo(200);
        assertThat(sql.queryForObject("select token_ciphertext from device_token where device_id='owned-test-install'",String.class)).startsWith("v1:").isNotEqualTo("test-only-owned-token");
        assertThat(http(other,"DELETE","/api/v1/notification-devices/owned-test-install",null).statusCode()).isEqualTo(403);
        assertThat(http(member,"DELETE","/api/v1/notification-devices/owned-test-install",null).statusCode()).isEqualTo(204);
        String raw="홍길동 010-1234-5678 user@example.com 37.123,127.456 지진 대피 방법";
        assertThat(http(member,"POST","/api/v1/ai/guidance",Map.of("question",raw)).body()).contains("FALLBACK");
        var reviewer=actor();sql.update("insert into member_role values(?,'OPS',?)",reviewer.memberId(),Timestamp.from(Instant.now()));
        UUID resource=UUID.randomUUID();
        var doc=new OfficialResourceDocument(resource,"행정안전부","지진 대피 테스트 Fixture","https://www.safekorea.go.kr/owned-fixture","fixture",
            "테스트 Fixture입니다. 지진 발생 시 안전한 대피 방법은 공식 대피 안내를 확인합니다.",Instant.now().minusSeconds(10));
        app.getBean(JdbcOfficialResourceStore.class).append(doc);
        var approval=app.getBean(DatabaseResourceApproval.class);
        assertThatThrownBy(()->approval.review(member,resource,doc.revisionFingerprint(),Instant.now().plusSeconds(600),"TEST REVIEW",true,true))
            .isInstanceOf(com.disasterinfo.eodiisseo.auth.exception.ApiProblem.class);
        approval.review(reviewer,resource,doc.revisionFingerprint(),Instant.now().plusSeconds(600),"TEST REVIEW",true,true);
        app.getBean(ExternalAiConsentService.class).record(member.memberId(),member.sessionId(),true);
        assertThat(http(member,"POST","/api/v1/ai/guidance",Map.of("question",raw)).body()).contains("\"answerType\":\"AI\"");
        assertThat(app.getBean(FakeChat.class).lastQuestion).isEqualTo("지진 발생 시 안전한 대피 방법");
        approval.revoke(reviewer,resource);
        assertThat(http(member,"POST","/api/v1/ai/guidance",Map.of("question",raw)).body()).contains("FALLBACK");
        app.getBean(AuthService.class).withdraw(member);
        assertThat(sql.queryForObject("select count(*) from ai_answer where member_id=?",Long.class,member.memberId())).isZero();
    }

    @Test @Order(5) void durableSummaryRestartsAndOwnedRetentionAvoidsMissingRawTables() throws Exception {
        app.getBean(FakeSummary.class).enabled=false;
        UUID disaster=write("restart-"+UUID.randomUUID(),"restart");drain();UUID revision=revision(disaster);
        assertThat(sql.queryForObject("select state from summary_runtime_task where revision_id=?",String.class,revision)).isEqualTo("HELD");
        app.close();open();
        sql.update("update summary_runtime_task set available_at=? where revision_id=?",Timestamp.from(Instant.now().minusSeconds(1)),revision);
        drain();
        assertThat(sql.queryForObject("select status from ai_summary where source_revision_id=?",String.class,revision)).isEqualTo("COMPLETED");
        sql.update("update disaster set status='ENDED',ended_at=? where disaster_id=?",Timestamp.from(Instant.now().minus(Duration.ofDays(91))),disaster);
        assertThat(app.getBean(DisasterRetentionService.class).purgeOne(Instant.now())).isTrue();
        assertThat(sql.queryForObject("select count(*) from disaster where disaster_id=?",Long.class,disaster)).isZero();
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class Providers {
        @Bean @Primary SocialIdentityVerifier fixtureSocial() {
            var verifier=mock(SocialIdentityVerifier.class);
            when(verifier.verify(anyString(),anyString())).thenAnswer(call->new SocialIdentityVerifier.Identity(call.getArgument(0),call.getArgument(1),"TEST MEMBER"));
            return verifier;
        }
        @Bean FakeSummary fakeSummary(){return new FakeSummary();}
        @Bean FakeChat fakeChat(){return new FakeChat();}
    }
    static class FakeSummary implements SummaryGenerator {
        volatile boolean enabled=true;
        public boolean isConfigured(){return enabled;}
        public Generated generate(OfficialRevision revision){return new Generated("TEST SUMMARY","test-model",Instant.now());}
    }
    static class FakeChat implements ChatAnswerGenerationPort {
        volatile String lastQuestion;
        public boolean isConfigured(){return true;}
        public GeneratedAnswer generate(GenerationRequest request){
            lastQuestion=request.question();
            return new GeneratedAnswer(request.evidence().getFirst().content(),List.of(request.evidence().getFirst().resourceId()),true,"test-chat");
        }
    }
}

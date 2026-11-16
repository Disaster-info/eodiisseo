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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        // KMA source is installed by Flyway; verify it rather than inserting a duplicate fixture.
        assertThat(sql.queryForObject("select count(*) from data_source where source_id=? and source_kind='DISASTER' and endpoint_key='KMA_EARTHQUAKE' and active=true",Long.class,SOURCE)).isEqualTo(1L);
    }
    @AfterEach
    void restoreSummaryFixture() {
        if (app != null && app.isActive()) {
            FakeSummary fake = app.getBean(FakeSummary.class);
            fake.enabled = true;
            fake.delegate = null;
        }
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
        return tx.execute(status -> {
            var provider = com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE;
            long sequence = "second".equals(tag) ? 2L : 1L;
            String issued = sequence == 1L ? "202611011230" : "202611011330";
            var payload = Map.<String,Object>of(
                "stnId", "108", "tmFc", issued, "tmSeq", Long.toString(sequence),
                "tmEqk", "20261101120000", "lat", "37.1", "lon", "127.1",
                "loc", "TEST FIXTURE " + tag, "message", "TEST ORIGINAL " + tag
            );
            allowIncident(SOURCE, provider, payload, key, sequence,
                com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,
                Instant.parse("2026-11-01T03:00:00Z"), null);
            var result = app.getBean(VerifiedIncidentService.class).normalize(
                SOURCE, provider, payload,
                com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(payload),
                Instant.now());
            assertThat(result.classification()).isIn("EARTHQUAKE_LINKED", "INCIDENT_DUPLICATE");
            sql.update("insert into collection_run(run_id,source_id,mode,started_at,finished_at,status,trigger_type) values(?,?,'LIVE',?,?,'SUCCESS','MANUAL')",
                UUID.randomUUID(), SOURCE, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key=?",
                UUID.class, SOURCE, "incident:" + key);
        });
    }
    private UUID revision(UUID disaster) {return sql.queryForObject("select r.revision_id from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=? order by revision_no desc limit 1",UUID.class,disaster);}
    private void drain() {
        var worker=app.getBean(DisasterRuntimeWorker.class);
        for(int i=0;i<20 && worker.drainOneEvent();i++){}
        for(int i=0;i<20 && worker.drainOneSummary();i++){}
    }

    @Test @Order(1) void protectedOwnerBoundariesAndConnectedPortsEnforceOwnership() throws Exception {
        assertThat(app.getEnvironment().getProperty("AUTH_BRIDGES_ENABLED")).isEqualTo("true");
        assertThat(app.getBeansOfType(DashboardDisasterQueryReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(DashboardDisasterReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(SummarySourceReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(SeverityReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(DisasterReader.class)).hasSize(1);
        assertThat(app.getBeansOfType(Authentication.class)).hasSize(1);
        assertThat(app.getBeansOfType(InterestAreas.class)).hasSize(1);
        assertThat(app.getBeansOfType(DashboardLocationReader.class)).hasSize(1);
        var member=actor();
        assertThat(sql.queryForObject("select count(*) from notification_setting where member_id=?",Long.class,member.memberId())).isZero();
        assertThat(http(member,"GET","/api/v1/notification-settings",null).statusCode()).isEqualTo(500);
        assertThat(http(member,"GET","/api/v1/dashboard?latitude=37&longitude=127",null).statusCode()).isEqualTo(500);
        // An explicit owned lifecycle call is possible; this does not claim automatic signup integration.
        app.getBean(NotificationRetentionService.class).onMemberCreated(member.memberId(),Instant.now());
        assertThat(sql.queryForObject("select enabled from notification_setting where member_id=?",Boolean.class,member.memberId())).isFalse();
        assertThat(http(member,"GET","/api/v1/notification-settings",null).statusCode()).isEqualTo(200); // Real area port is connected.
        UUID[] rolled={null};
        tx.executeWithoutResult(status->{
            var nested=actor();rolled[0]=nested.memberId();
            app.getBean(NotificationRetentionService.class).onMemberCreated(nested.memberId(),Instant.now());
            status.setRollbackOnly();
        });
        assertThat(sql.queryForObject("select count(*) from member where member_id=?",Long.class,rolled[0])).isZero();
        assertThat(sql.queryForObject("select count(*) from notification_setting where member_id=?",Long.class,rolled[0])).isZero();

        tx.executeWithoutResult(status->{
            sql.update("insert into admin_area(area_code,name,level,center_point,active) values('OWNED_TEST_AREA','SYNTHETIC TEST REGION','SIGUNGU',ST_SetSRID(ST_MakePoint(127.1,37.1),4326)::geography,true)");
            var foreign=actor();
            var created=app.getBean(com.disasterinfo.eodiisseo.area.service.AreaService.class)
                .create(member.memberId(),"OWNED_TEST_AREA","TEST OWNED AREA");
            UUID area=UUID.fromString(created.get("interestAreaId").toString());
            var interests=app.getBean(InterestAreas.class);
            assertThat(interests.findOwned(member.memberId())).anyMatch(a->a.id().equals(area));
            assertThat(interests.findOwned(foreign.memberId())).noneMatch(a->a.id().equals(area));
            assertThatThrownBy(()->interests.replaceSelection(foreign.memberId(),Set.of(area)))
                .isInstanceOfSatisfying(NotificationException.class,e->assertThat(e.reason()).isEqualTo(NotificationException.Reason.FORBIDDEN));
            var locations=app.getBean(DashboardLocationReader.class);
            var base=locations.resolveInterestArea(member.memberId(),area);
            assertThat(base.latitude()).isEqualTo(37.1);
            assertThat(base.longitude()).isEqualTo(127.1);
            assertThatThrownBy(()->locations.resolveInterestArea(foreign.memberId(),area))
                .isInstanceOfSatisfying(com.disasterinfo.eodiisseo.dashboard.DashboardException.class,
                    e->assertThat(e.reason()).isEqualTo(com.disasterinfo.eodiisseo.dashboard.DashboardException.Reason.FORBIDDEN));
            assertThatThrownBy(()->locations.resolveInterestArea(member.memberId(),UUID.randomUUID()))
                .isInstanceOfSatisfying(com.disasterinfo.eodiisseo.dashboard.DashboardException.class,
                    e->assertThat(e.reason()).isEqualTo(com.disasterinfo.eodiisseo.dashboard.DashboardException.Reason.NOT_FOUND));
            status.setRollbackOnly();
        });
        String tables=" from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name not in ('spatial_ref_sys','flyway_schema_history')";
        String ownership="('collection_contract','collection_target','collection_source_assignment','collection_target_lease','collection_run_resolution')";
        assertThat(sql.queryForObject("select count(*)"+tables+" and table_name not in "+ownership,Long.class)).isEqualTo(50);
        assertThat(sql.queryForObject("select count(*)"+tables+" and table_name in "+ownership,Long.class)).isEqualTo(5);
        assertThat(sql.queryForObject("select count(*)"+tables,Long.class)).isEqualTo(55);
    }

    @Test @Order(2) void ownedReadsRevisionOutboxAndSummaryWorkWithoutBorrowingACollector() throws Exception {
        var member=actor();
        assertThat(http(null,"GET","/api/v1/disasters",null).statusCode()).isEqualTo(401);
        assertThat(http(member,"GET","/api/v1/disasters",null).body()).contains("\"disasters\":[]");

        tx.executeWithoutResult(status->{
            long before=sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class);
            String forged="incident:untrusted-"+UUID.randomUUID();
            app.getBean(DisasterService.class).earthquake(SOURCE,forged,
                Map.of("tmEqk","20261101120000","lat","37.1","lon","127.1","loc","TEST UNVERIFIED"),
                "TEST UNVERIFIED PREFIX ONLY",Instant.now());
            assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class)).isEqualTo(before);
            assertThat(sql.queryForObject("select count(*) from owned_incident_head where incident_key=?",Long.class,forged.substring(9))).isZero();
            status.setRollbackOnly();
        });
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
        // Verified incident creates one NEW and one REVISION event; delivery is an independent policy decision.
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=? and event_kind='NEW'",Long.class,disaster)).isEqualTo(1L);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=? and event_kind='REVISION'",Long.class,disaster)).isEqualTo(1L);
        write(key,"second");
        assertThat(http(member,"GET","/api/v1/disasters/"+disaster+"/ai-summary",null).body()).contains("STALE");
        drain();
        assertThat(sql.queryForObject("select status from ai_summary where source_revision_id=?",String.class,first)).isEqualTo("STALE");
        assertThat(app.getBean(SummarySourceReader.class).isCurrentRevision(first)).isFalse();
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=? and event_kind='NEW'",Long.class,disaster)).isEqualTo(1L);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=? and event_kind='REVISION'",Long.class,disaster)).isEqualTo(2L);
        assertThat(http(member,"GET","/api/v1/disasters/"+disaster+"/ai-summary",null).body()).contains("COMPLETED");

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


    @Test @Order(6) void collectorStoresOfficialFactsAndRollsBackOnFailure() throws Exception {
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE;
        var service=app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionService.class);
        var payload=new java.util.LinkedHashMap<String,Object>(Map.of("stnId","108","tmFc","202611011230","tmSeq","10",
            "tmEqk","20261101120000","lat","37.1","lon","127.1","loc","TEST ONLY"));
        java.util.concurrent.atomic.AtomicReference<Map<String,Object>> current=new java.util.concurrent.atomic.AtomicReference<>(payload);
        var client=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->{
            String body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("response",Map.of("header",Map.of("resultCode","00"),
                "body",Map.of("pageNo",1,"totalCount",1,"items",Map.of("item",List.of(current.get()))))));
            return new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(200,body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        allowIncident(SOURCE,provider,payload,"fixture-quake-10",1,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,
            Instant.parse("2026-11-01T03:00:00Z"),null);
        LocalDate date=LocalDate.of(2026,11,1);
        assertThat(service.collect(SOURCE,client,"test-not-a-key",date,date,"owned-disaster-development").status()).isEqualTo("SUCCESS");
        String recordKey="incident:fixture-quake-10";
        UUID id=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key=?",UUID.class,SOURCE,recordKey);
        assertThat(sql.queryForObject("select official_severity_id from disaster where disaster_id=?",UUID.class,id)).isNull();
        assertThat(sql.queryForObject("select count(*) from disaster_area where disaster_id=?",Long.class,id)).isZero();
        resetCollector(SOURCE);
        assertThat(service.collect(SOURCE,client,"test-not-a-key",date,date,"owned-disaster-development").normalized()).isZero();
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=?",Long.class,id)).isEqualTo(1);
        payload.put("cor","TEST CORRECTION");current.set(payload);resetCollector(SOURCE);
        allowIncident(SOURCE,provider,payload,"fixture-quake-10",2,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,Instant.parse("2026-11-01T03:00:00Z"),null);
        assertThat(service.collect(SOURCE,client,"test-not-a-key",date,date,"owned-disaster-development").status()).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=?",Long.class,id)).isEqualTo(2);
        drain();
        assertThat(http(actor(),"GET","/api/v1/disasters/"+id+"/ai-summary",null).body()).contains("COMPLETED");
        long items=sql.queryForObject("select count(*) from owned_collection_item",Long.class);
        resetCollector(SOURCE);
        var broken=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->
            new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(429,new byte[0]));
        assertThat(service.collect(SOURCE,broken,"test",date,date,"owned-disaster-development").status()).isEqualTo("RATE_LIMIT");
        assertThat(sql.queryForObject("select count(*) from owned_collection_item",Long.class)).isEqualTo(items);
        assertThat(http(actor(),"GET","/api/v1/disasters/"+id,null).body()).contains("STALE");
        assertThat(service.collect(SOURCE,client,"test",date,date,"owned-disaster-development").status()).isEqualTo("BUSY_OR_RATE_LIMITED");
        resetCollector(SOURCE);
        var failing=mock(com.disasterinfo.eodiisseo.disaster.collection.OfficialRecordNormalizer.class);
        doThrow(new IllegalStateException("TEST persistence failure")).when(failing).normalize(any(),any(),any(),any(),any());
        var rollback=new com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionService(
            app.getBean(com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.class),failing,app.getBean(PlatformTransactionManager.class),Clock.systemUTC());
        payload.put("cor","TEST FAIL ROLLBACK");
        long pages=sql.queryForObject("select count(*) from owned_collection_page",Long.class);
        assertThat(rollback.collect(SOURCE,client,"test",date,date,"owned-disaster-development").status()).isEqualTo("PERSISTENCE_OR_NORMALIZATION");
        assertThat(sql.queryForObject("select count(*) from owned_collection_page",Long.class)).isEqualTo(pages);
        assertThat(sql.queryForObject("select count(*) from owned_collection_item",Long.class)).isEqualTo(items);
    }
    private void resetCollector(UUID source){sql.update("update owned_collection_cursor set next_attempt_at=? where source_id=?",Timestamp.from(Instant.now().minusSeconds(1)),source);}

    @Test @Order(7) void rainLifecycleAndUnlinkedFireNeverInventAreasOrEvents(){
        var normalizer=app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialRecordNormalizer.class);
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.HEAVY_RAIN;
        UUID source=UUID.randomUUID(),fire=UUID.randomUUID();
        // Exclusive synthetic provider configuration in this disposable DB; dual active aliases must be held.
        sql.update("update data_source set active=false where endpoint_key in ('KMA_WARNING','SAFETYDATA_DISASTER_MESSAGE')");
        sql.update("insert into data_source values(?,'TEST RAIN','DISASTER','KMA_HEAVY_RAIN',5,true)",source);
        sql.update("insert into data_source values(?,'TEST MESSAGES','DISASTER','MOIS_DISASTER_MESSAGES',5,true)",fire);
        var row=new java.util.LinkedHashMap<String,Object>(Map.of("stnId","108","tmFc","202611011000","tmSeq","1","areaCode","L_TEST","areaName","TEST ZONE",
            "warnVar","2","warnStress","0","command","1","startTime","202611011000","cancel","0"));
        var first=tx.execute(status->normalizer.normalize(source,provider,row,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(row),Instant.now()));
        assertThat(first.sourceRecord()).isNotNull();
        // SQL wildcard characters in one source code must never match a different exact warning zone.
        var otherZone=new java.util.LinkedHashMap<String,Object>(row);
        otherZone.put("areaCode","LXTEST");otherZone.put("tmFc","202611011030");otherZone.put("startTime","202611011030");
        var other=tx.execute(status->normalizer.normalize(source,provider,otherZone,
            com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(otherZone),Instant.now()));
        assertThat(other.sourceRecord()).isNotEqualTo(first.sourceRecord());
        row.put("command","7");row.put("warnStress","1");row.put("tmFc","202611011100");row.put("tmSeq","2");
        assertThat(tx.execute(status->normalizer.normalize(source,provider,row,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(row),Instant.now())).sourceRecord()).isEqualTo(first.sourceRecord());
        row.put("command","2");row.remove("startTime");row.put("endTime","202611011200");row.put("tmFc","202611011200");row.put("tmSeq","3");
        assertThat(tx.execute(status->normalizer.normalize(source,provider,row,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(row),Instant.now())).classification()).isEqualTo("HEAVY_RAIN_LIFTED");
        assertThat(sql.queryForObject("select d.status from disaster d join source_disaster s using(disaster_id) where source_disaster_id=?",String.class,first.sourceRecord())).isEqualTo("LIFTED");
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision where source_disaster_id=?",Long.class,first.sourceRecord())).isEqualTo(3);
        assertThat(sql.queryForObject("select count(*) from disaster_area where source_disaster_id=?",Long.class,first.sourceRecord())).isZero();
        var message=Map.<String,Object>of("SN","TEST MESSAGE","DST_SE_NM","산불","MSG_CN","TEST 산불 발생 안내");
        long count=sql.queryForObject("select count(*) from disaster",Long.class);
        var unlinked=tx.execute(status->normalizer.normalize(fire,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES,
            message,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(message),Instant.now()));
        assertThat(unlinked.classification()).isEqualTo("WILDFIRE_MESSAGE_UNLINKED");assertThat(unlinked.sourceRecord()).isNull();
        assertThat(sql.queryForObject("select count(*) from disaster",Long.class)).isEqualTo(count);
    }

    @Test @Order(8) void importedGuidanceRequiresReviewAndChangedPublicationRevokesOldApproval(){
        var importer=app.getBean(com.disasterinfo.eodiisseo.ai.resource.adapter.OfficialGuidanceImporter.class);
        var approval=app.getBean(DatabaseResourceApproval.class);
        var reviewer=actor();var ordinary=actor();
        sql.update("insert into member_role values(?,'OPS',?)",reviewer.memberId(),Timestamp.from(Instant.now()));
        String url="https://www.mois.go.kr/owned-import-test";
        byte[] raw="<html><body><div id='guide'><p>TEST ONLY 지진 대피 방법은 공식 안내를 확인합니다.</p></div></body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant expiry=Instant.now().plusSeconds(600);
        assertThatThrownBy(()->importer.publish(ordinary,url,"TEST GUIDE","guide",raw,expiry)).isInstanceOf(com.disasterinfo.eodiisseo.auth.exception.ApiProblem.class);
        var first=importer.publish(reviewer,url,"TEST GUIDE","guide",raw,expiry);
        assertThat(approval.findApproval(first.getResourceId())).isEmpty();
        assertThat(sql.queryForObject("select raw_body from owned_resource_import where resource_id=?",byte[].class,first.getResourceId())).isEqualTo(raw);
        assertThat(importer.publish(reviewer,url,"TEST GUIDE","guide",raw,expiry).getResourceId()).isEqualTo(first.getResourceId());
        approval.review(reviewer,first.getResourceId(),first.revisionFingerprint(),expiry,"TEST HUMAN REVIEW",true,true);
        assertThat(approval.findApproval(first.getResourceId())).isPresent();
        byte[] changed=new String(raw,java.nio.charset.StandardCharsets.UTF_8).replace("TEST ONLY","TEST CHANGED").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var next=importer.publish(reviewer,url,"TEST GUIDE","guide",changed,expiry);
        assertThat(next.getResourceId()).isNotEqualTo(first.getResourceId());
        assertThat(approval.findApproval(first.getResourceId())).isEmpty();
        assertThat(approval.findApproval(next.getResourceId())).isEmpty();
        assertThatThrownBy(()->approval.review(reviewer,first.getResourceId(),first.revisionFingerprint(),expiry,"TEST",true,true)).isInstanceOf(IllegalArgumentException.class);
        sql.update("update owned_resource_import set expires_at=? where resource_id=?",Timestamp.from(Instant.now().minusSeconds(1)),next.getResourceId());
        assertThatThrownBy(()->approval.review(reviewer,next.getResourceId(),next.revisionFingerprint(),expiry,"TEST",true,true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(importer.purgeExpired(reviewer)).isEqualTo(1);
        assertThat(sql.queryForObject("select octet_length(raw_body) from owned_resource_import where resource_id=?",Integer.class,next.getResourceId())).isZero();
    }


    @Test @Order(9) void loopbackProviderToRealOllamaClientToStoredSummary() throws Exception {
        // Both servers are local test doubles. Production HTTP clients, normalizer, DB, outbox and worker are real.
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var row=Map.<String,Object>of("stnId","108","tmFc","202611011300","tmSeq","11","tmEqk","20261101125900","lat","37.1","lon","127.1","loc","TEST END TO END");
        allowIncident(SOURCE,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE,row,"fixture-quake-11",1,
            com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,Instant.parse("2026-11-01T03:59:00Z"),null);
        String providerBody=mapper.writeValueAsString(Map.of("response",Map.of("header",Map.of("resultCode","00"),
            "body",Map.of("pageNo",1,"totalCount",1,"items",Map.of("item",List.of(row))))));
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/provider",exchange->{
            byte[] body=providerBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.createContext("/api/chat",exchange->{
            var request=mapper.readTree(exchange.getRequestBody().readAllBytes());
            var envelope=mapper.readTree(request.path("messages").get(1).path("content").asString());
            String selected=envelope.path("allowedParagraphs").get(0).asString();
            String content=mapper.writeValueAsString(Map.of("excerpts",List.of(selected)));
            byte[] body=mapper.writeValueAsBytes(Map.of("model","fixture:e2e","done",true,"message",Map.of("role","assistant","content",content)));
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();
        URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        var settings=new com.disasterinfo.eodiisseo.ai.ollama.OllamaSettings(base,"fixture:e2e",Duration.ofSeconds(5),Duration.ofSeconds(2),Set.of("127.0.0.1"));
        var fake=app.getBean(FakeSummary.class);
        try(var http=new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp();var model=new com.disasterinfo.eodiisseo.ai.ollama.OllamaChatClient(settings)){
            fake.delegate=new com.disasterinfo.eodiisseo.ai.summary.adapter.OllamaSummaryGenerator(model);
            var client=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(
                com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE,
                (uri,timeout)->http.get(base.resolve("/provider"),timeout));
            resetCollector(SOURCE);
            LocalDate date=LocalDate.of(2026,11,1);
            assertThat(app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionService.class)
                .collect(SOURCE,client,"test",date,date,"owned-disaster-development").status()).isEqualTo("SUCCESS");
            UUID disaster=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key='incident:fixture-quake-11'",UUID.class,SOURCE);
            String original=sql.queryForObject("select original_text from source_disaster where disaster_id=?",String.class,disaster);
            UUID revision=revision(disaster);
            assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where revision_id=? and event_kind='REVISION'",Long.class,revision)).isEqualTo(1);
            var worker=app.getBean(DisasterRuntimeWorker.class);
            for(int i=0;i<100&&worker.drainOneEvent();i++){}
            assertThat(sql.queryForObject("select status from ai_summary where source_revision_id=?",String.class,revision)).isEqualTo("PENDING");
            for(int i=0;i<100&&worker.drainOneSummary();i++){}
            assertThat(sql.queryForObject("select status from ai_summary where source_revision_id=?",String.class,revision))
                .as("Summary task=%s | Outbox=%s | AI errors=%s",
                    sql.queryForList("select state,last_error_code,attempts from summary_runtime_task where revision_id=?",revision),
                    sql.queryForList("select event_kind,state,last_error_code,attempts from disaster_runtime_outbox where revision_id=?",revision),
                    sql.queryForList("select error_code from service_error_log where component='AISUM' order by occurred_at desc limit 5"))
                .isEqualTo("COMPLETED");
            assertThat(sql.queryForObject("select model_name from ai_summary where source_revision_id=?",String.class,revision)).isEqualTo("fixture:e2e");
            assertThat(sql.queryForObject("select original_text from source_disaster where disaster_id=?",String.class,disaster)).isEqualTo(original);
            assertThat(http(actor(),"GET","/api/v1/disasters/"+disaster+"/ai-summary",null).body()).contains("COMPLETED");
        }finally{fake.delegate=null;server.stop(0);}
    }


    @Test @Order(10) void dailyBudgetAndCanonicalLeasePreventAnyNetworkRequest(){
        var service=app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionService.class);
        var client=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(
            com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE,(uri,timeout)->{throw new AssertionError("No network allowed");});
        LocalDate day=LocalDate.of(2026,11,1);
        assertThatThrownBy(()->service.collect(SOURCE,client,"test",day,day,"unconfirmed")).isInstanceOf(com.disasterinfo.eodiisseo.disaster.collection.CollectionFailure.class);
        resetCollector(SOURCE);
        sql.update("update owned_collection_cursor set budget_day=?,requests_today=1000 where source_id=?",java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)),SOURCE);
        assertThat(service.collect(SOURCE,client,"test",day,day,"owned-disaster-development").status()).isEqualTo("DAILY_BUDGET_EXHAUSTED");
        resetCollector(SOURCE);
        UUID other=UUID.randomUUID();
        sql.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type) values(?,?,'LIVE',?,'RUNNING','MANUAL')",other,SOURCE,Timestamp.from(Instant.now()));
        sql.update("insert into collection_job_lock(source_id,mode,run_id,lease_until) values(?,'LIVE',?,?)",SOURCE,other,Timestamp.from(Instant.now().plusSeconds(600)));
        assertThat(service.collect(SOURCE,client,"test",day,day,"owned-disaster-development").status()).isEqualTo("BUSY_OR_RATE_LIMITED");
        assertThat(app.getBeansOfType(com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionScheduler.class)).isEmpty();
    }


    private void allowIncident(UUID source,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider provider,
        Map<String,Object> row,String event,long sequence,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State state,Instant occurred,Instant ended){
        String key=com.disasterinfo.eodiisseo.disaster.collection.OfficialRecordNormalizer.identity(provider,row);
        String hash=AuthService.hash(com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(row));
        Instant issued=provider==com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE?
            com.disasterinfo.eodiisseo.disaster.collection.OfficialRecordNormalizer.time(row.get("tmFc")):
            LocalDateTime.parse((String)row.get("CRT_DT"),java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        var link=new com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.Link(source,provider,key,hash,event,sequence,issued,occurred,state,ended,
            URI.create("https://www.safetydata.go.kr/test-only-contract"),"SYNTHETIC TEST CONTRACT - NOT A REAL PROVIDER RULE");
        app.getBean(FixtureLinks.class).links.put(source+":"+key+":"+hash,link);
    }
    static class FixtureLinks implements com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort{
        final Map<String,Link> links=new java.util.concurrent.ConcurrentHashMap<>();
        public Optional<Link> resolve(UUID source,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider provider,String key,String hash){
            return Optional.ofNullable(links.get(source+":"+key+":"+hash));
        }
    }
    static class FixtureMappings implements com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort{
        final Map<String,Mapping> mappings=new java.util.concurrent.ConcurrentHashMap<>();
        public Optional<Mapping> resolve(String zone,Instant issued,Instant asOf){return Optional.ofNullable(mappings.get(zone));}
    }

    private UUID source(String endpoint){return sql.queryForObject("select source_id from data_source where endpoint_key=?",UUID.class,endpoint);}
    private void ready(UUID source){
        sql.update("delete from collection_job_lock where source_id=?",source);
        sql.update("update owned_collection_cursor set next_attempt_at=?,requests_today=0 where source_id=?",Timestamp.from(Instant.now().minusSeconds(1)),source);
    }
    private com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient fixtureClient(
        com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider provider,java.util.function.Supplier<List<Map<String,Object>>> rows){
        return new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->{
            var values=rows.get();Object envelope=provider==com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES?
                Map.of("header",Map.of("resultCode","00"),"totalCount",values.size(),"body",values):
                Map.of("response",Map.of("header",Map.of("resultCode","00"),"body",Map.of("pageNo",1,"totalCount",values.size(),"items",Map.of("item",values))));
            return new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(200,tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(envelope));
        });
    }
    private String collect(UUID source,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient client){
        ready(source);return app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionService.class)
            .collect(source,client,"SYNTHETIC KEY",LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),"owned-disaster-development").status();
    }

    @Test @Order(11) void fireRemainsRawUntilVerifiedThenDeduplicatesRevisesAndEnds() throws Exception {
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES;
        UUID source=source("MOIS_DISASTER_MESSAGES");
        var row=new LinkedHashMap<String,Object>(Map.of("SN","fire-message-1","DST_SE_NM","산불","CRT_DT","2026-11-01 10:00:00",
            "MSG_CN","TEST ONLY 산불 발생","RCPTN_RGN_NM","TEST recipient"));
        var client=fixtureClient(provider,()->List.of(row));
        long disasters=sql.queryForObject("select count(*) from disaster",Long.class);
        assertThat(collect(source,client)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        assertThat(sql.queryForObject("select count(*) from disaster",Long.class)).isEqualTo(disasters);
        assertThat(sql.queryForObject("select classification from owned_collection_item where source_id=? and provider_record_key='fire-message-1'",String.class,source)).isEqualTo("WILDFIRE_MESSAGE_UNLINKED");
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source)).isNull();
        var occurred=Instant.parse("2026-11-01T01:00:00Z");
        allowIncident(source,provider,row,"synthetic-fire",1,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,occurred,null);
        assertThat(collect(source,client)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED"); // Same archived bytes are now eligible for verified linking.
        UUID disaster=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key='incident:synthetic-fire'",UUID.class,source);
        assertThat(sql.queryForObject("select count(*) from owned_collection_item where source_id=? and provider_record_key='fire-message-1'",Long.class,source)).isEqualTo(1);
        assertThat(collect(source,client)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=?",Long.class,disaster)).isEqualTo(1);
        row.put("SN","fire-message-2");row.put("CRT_DT","2026-11-01 11:00:00");row.put("MSG_CN","TEST ONLY 공식 후속 발표");
        allowIncident(source,provider,row,"synthetic-fire",2,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.ONGOING,occurred,null);
        assertThat(collect(source,client)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        row.put("SN","fire-message-old");row.put("CRT_DT","2026-11-01 10:30:00");
        allowIncident(source,provider,row,"synthetic-fire",1,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,occurred,null);
        assertThat(collect(source,client)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        assertThat(sql.queryForObject("select classification from owned_collection_item where source_id=? and provider_record_key='fire-message-old'",String.class,source)).isEqualTo("OUT_OF_ORDER_INCIDENT");
        row.put("SN","fire-message-3");row.put("CRT_DT","2026-11-01 12:00:00");row.put("MSG_CN","TEST ONLY 공식 진화 발표");
        allowIncident(source,provider,row,"synthetic-fire",3,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.ENDED,occurred,Instant.parse("2026-11-01T02:59:00Z"));
        assertThat(collect(source,client)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        assertThat(sql.queryForObject("select status from disaster where disaster_id=?",String.class,disaster)).isEqualTo("ENDED");
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=?",Long.class,disaster)).isEqualTo(3);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=?",Long.class,disaster)).isEqualTo(4);
        assertThat(sql.queryForObject("select count(*) from disaster_area where disaster_id=?",Long.class,disaster)).isZero();
        var member=actor();
        assertThat(http(member,"GET","/api/v1/disasters?type=WILDFIRE",null).body()).contains(disaster.toString());
        assertThat(http(member,"GET","/api/v1/disasters/"+disaster,null).body()).contains("ENDED","공식 진화 발표");
        var failed=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(503,new byte[0]));
        assertThat(collect(source,failed)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(sql.queryForObject("select status from disaster where disaster_id=?",String.class,disaster)).isEqualTo("ENDED");
    }

    @Test @Order(12) void earthquakeUnknownFollowupIsRawAndOlderVerifiedSequenceCannotRewind(){
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE;
        var row=new LinkedHashMap<String,Object>(Map.of("stnId","108","tmFc","202611011230","tmSeq","21",
            "tmEqk","20261101120000","lat","37.1","lon","127.1","loc","TEST FOLLOWUP","cnt","same-looking-reference"));
        var client=fixtureClient(provider,()->List.of(row));
        long before=sql.queryForObject("select count(*) from disaster",Long.class);
        assertThat(collect(SOURCE,client)).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select count(*) from disaster",Long.class)).isEqualTo(before);
        var occurred=Instant.parse("2026-11-01T03:00:00Z");
        allowIncident(SOURCE,provider,row,"synthetic-followup",1,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,occurred,null);
        assertThat(collect(SOURCE,client)).isEqualTo("SUCCESS");
        row.put("tmFc","202611011330");row.put("tmSeq","22");row.put("cor","TEST corrected position");row.put("lat","37.2");
        allowIncident(SOURCE,provider,row,"synthetic-followup",2,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,occurred,null);
        assertThat(collect(SOURCE,client)).isEqualTo("SUCCESS");
        UUID disaster=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key='incident:synthetic-followup'",UUID.class,SOURCE);
        String original=sql.queryForObject("select original_text from source_disaster where disaster_id=?",String.class,disaster);
        row.put("tmFc","202611011300");row.put("tmSeq","23");row.put("lat","37.0");
        allowIncident(SOURCE,provider,row,"synthetic-followup",1,com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,occurred,null);
        assertThat(collect(SOURCE,client)).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select original_text from source_disaster where disaster_id=?",String.class,disaster)).isEqualTo(original);
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=?",Long.class,disaster)).isEqualTo(2);
        row.put("tmFc","202611011400");row.put("tmSeq","24");row.put("incidentId","synthetic-followup");
        assertThat(collect(SOURCE,client)).isEqualTo("SUCCESS"); // Untrusted payload event ID does not grant an association.
        assertThat(sql.queryForObject("select count(*) from disaster",Long.class)).isEqualTo(before+1);
    }

    @Test @Order(13) void exactOfficialMappingIsUsedForIssueChangeLiftAndWithdrawal() {
        UUID source=source("KMA_HEAVY_RAIN");
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.HEAVY_RAIN;
        var row=new LinkedHashMap<String,Object>(Map.of("stnId","108","tmFc","202611011000","tmSeq","40","areaCode","L_PHASE2","areaName","비슷한 이름",
            "warnVar","2","warnStress","0","command","1","startTime","202611011000","cancel","0"));
        var client=fixtureClient(provider,()->List.of(row));assertThat(collect(source,client)).isEqualTo("SUCCESS");
        UUID disaster=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key like 'L_PHASE2:%'",UUID.class,source);
        var reader=app.getBean(com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader.class);
        assertThat(reader.find(disaster,Instant.now()).knowledge()).isEqualTo(com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader.Knowledge.UNKNOWN);
        sql.update("insert into admin_area(area_code,name,level,active) values('TEST_EXACT','테스트 공식 매핑 구역','SIGUNGU',true)");
        var map=new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping("L_PHASE2",Set.of("TEST_EXACT"),
            URI.create("https://www.weather.go.kr/test-crosswalk"),"SYNTHETIC ONLY",Instant.parse("2026-01-01T00:00:00Z"),Instant.now().plusSeconds(600),true);
        var mappings=app.getBean(FixtureMappings.class).mappings;
        mappings.put("L_PHASE2",map);
        assertThat(reader.find(disaster,Instant.now()).exactAdminAreaCodes()).containsExactly("TEST_EXACT");
        assertThat(app.getBean(OfficialDisasterQueryService.class).region(disaster)).isEqualTo("테스트 공식 매핑 구역");
        row.put("command","7");row.put("warnStress","1");row.put("tmFc","202611011100");
        assertThat(collect(source,client)).isEqualTo("SUCCESS");
        assertThat(reader.find(disaster,Instant.now()).activeIncident()).isTrue();
        row.put("command","2");row.put("tmFc","202611011200");row.put("endTime","202611011200");row.remove("startTime");
        assertThat(collect(source,client)).isEqualTo("SUCCESS");
        assertThat(reader.find(disaster,Instant.now()).activeIncident()).isFalse();
        mappings.put("L_PHASE2",new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping("LXPHASE2",Set.of("TEST_EXACT"),map.evidenceUrl(),map.version(),map.validFrom(),map.validUntil(),true));
        assertThat(app.getBean(OfficialDisasterQueryService.class).region(disaster)).isNull();
        mappings.remove("L_PHASE2");
        assertThat(reader.find(disaster,Instant.now()).knowledge()).isEqualTo(com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader.Knowledge.UNKNOWN);
    }

    @Test @Order(14) void coverageNeverSkipsGapsAndChangingPagesRollback() {
        UUID source=SOURCE;
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE;
        var service=app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionService.class);
        sql.update("delete from owned_collection_coverage where source_id=?",source);
        sql.update("update owned_collection_cursor set coverage_start=null,completed_through=null where source_id=?",source);
        var empty=fixtureClient(provider,List::of);
        for(int[] window:new int[][]{{20,22},{25,27},{23,24}}){
            ready(source);
            assertThat(service.collect(source,empty,"test",LocalDate.of(2026,10,window[0]),LocalDate.of(2026,10,window[1]),"owned-disaster-development").status()).isEqualTo("SUCCESS");
            int expected=window[0]==23?27:22;
            assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source).toLocalDate()).isEqualTo(LocalDate.of(2026,10,expected));
        }
        long pages=sql.queryForObject("select count(*) from owned_collection_page where run_id in(select run_id from collection_run where source_id=?)",Long.class,source);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var moving=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->{
            int page=count.incrementAndGet();List<Map<String,Object>> rows=new ArrayList<>();
            for(int i=0;i<100;i++)rows.add(Map.of("SN","moving-"+i,"DST_SE_NM","TEST"));
            return new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(200,tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(
                Map.of("response",Map.of("header",Map.of("resultCode","00"),"body",Map.of("totalCount",page==1?101:102,"pageNo",page,"items",Map.of("item",rows))))));
        });
        assertThat(collect(source,moving)).isEqualTo("PAGING_CHANGED");
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source).toLocalDate()).isEqualTo(LocalDate.of(2026,10,27));
        assertThat(sql.queryForObject("select count(*) from owned_collection_page where run_id in(select run_id from collection_run where source_id=?)",Long.class,source)).isEqualTo(pages);
        UUID abandoned=UUID.randomUUID();ready(source);
        sql.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type) values(?,?,'LIVE',?,'RUNNING','MANUAL')",
            abandoned,source,Timestamp.from(Instant.now().minusSeconds(2000)));
        sql.update("insert into collection_job_lock(source_id,mode,run_id,lease_until) values(?,'LIVE',?,?)",source,abandoned,Timestamp.from(Instant.now().minusSeconds(1)));
        // A reconstructed service reads persisted cursor/lease state, with no in-memory recovery token.
        var recovered=new com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionService(app.getBean(com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase.class),
            app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialRecordNormalizer.class),app.getBean(PlatformTransactionManager.class),Clock.systemUTC());
        var held=recovered.collect(source,empty,"test",LocalDate.of(2026,10,26),LocalDate.of(2026,10,28),"owned-disaster-development");
        assertThat(held.status()).isEqualTo("LEASE_OWNERSHIP_UNVERIFIED");
        assertThat(sql.queryForObject("select status from collection_run where run_id=?",String.class,abandoned)).isEqualTo("RUNNING");
        assertThat(sql.queryForObject("select error_code from collection_run where run_id=?",String.class,abandoned)).isNull();
        assertThat(sql.queryForObject("select run_id from collection_job_lock where source_id=?",UUID.class,source)).isEqualTo(abandoned);
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source).toLocalDate()).isEqualTo(LocalDate.of(2026,10,27));
        // Simulate an explicit operator resolution in the fixture, not an automatic ownership guess.
        sql.update("update collection_run set status='FAILED',finished_at=?,error_code='TEST_OPERATOR_RESOLVED' where run_id=?",Timestamp.from(Instant.now()),abandoned);
        sql.update("delete from collection_job_lock where run_id=?",abandoned);
        assertThat(recovered.collect(source,empty,"test",LocalDate.of(2026,10,26),LocalDate.of(2026,10,28),"owned-disaster-development").status()).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select error_code from collection_run where run_id=?",String.class,abandoned)).isEqualTo("TEST_OPERATOR_RESOLVED");
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source).toLocalDate()).isEqualTo(LocalDate.of(2026,10,28));
    }

    @Test @Order(15) void offlineRagAdminToolRequiresOpsAndExplicitHumanReview() throws Exception {
        var tool=app.getBean(com.disasterinfo.eodiisseo.ai.resource.adapter.GuidanceAdminTool.class);
        var reviewer=actor();var ordinary=actor();
        sql.update("insert into member_role values(?,'OPS',?)",reviewer.memberId(),Timestamp.from(Instant.now()));
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        for(var hazard:List.of("EARTHQUAKE","HEAVY_RAIN","WILDFIRE")){
            String korean=hazard.equals("EARTHQUAKE")?"지진":hazard.equals("HEAVY_RAIN")?"호우":"산불";
            var command=new LinkedHashMap<String,Object>(Map.of("operation","IMPORT","hazard",hazard,"sourceUrl","https://www.mois.go.kr/phase2-"+hazard,
                "title","TEST ONLY "+korean+" 대피 행동요령","bodyElementId","guide","html","<div id='guide'><p>TEST ONLY "+korean+" 발생 시 안전한 대피 방법은 공식 안내를 확인합니다.</p></div>",
                "expiresAt",Instant.now().plusSeconds(600).toString()));
            assertThat(toolCall(tool,ordinary,command).get("status")).isEqualTo("REJECTED");
            var imported=toolCall(tool,reviewer,command);
            assertThat(imported.get("status")).isEqualTo("OK");
            var result=(Map<String,Object>)imported.get("result");
            UUID resource=UUID.fromString((String)result.get("resourceId"));
            assertThat(result.get("approved")).isEqualTo(false);
            var search=app.getBean(com.disasterinfo.eodiisseo.ai.resource.ResourceService.class);
            assertThat(search.search(korean+" 대피 방법").resources()).noneMatch(r->r.resourceId().equals(resource));
            var review=new LinkedHashMap<String,Object>(Map.of("operation","APPROVE","resourceId",resource.toString(),
                "expectedFingerprint",result.get("fingerprint"),"validUntil",Instant.now().plusSeconds(300).toString(),"evidenceReference","SYNTHETIC HUMAN REVIEW",
                "rightsAndExternalAiUseConfirmed",false,"injectionReviewed",true));
            assertThat(toolCall(tool,reviewer,review).get("status")).isEqualTo("REJECTED");
            review.put("rightsAndExternalAiUseConfirmed",true);
            assertThat(toolCall(tool,reviewer,review).get("status")).isEqualTo("OK");
            assertThat(search.search(korean+" 대피 방법").resources()).anyMatch(r->r.resourceId().equals(resource));
            assertThat(toolCall(tool,reviewer,Map.of("operation","REVOKE","resourceId",resource.toString())).get("status")).isEqualTo("OK");
            assertThat(search.search(korean+" 대피 방법").resources()).noneMatch(r->r.resourceId().equals(resource));
            command.put("html",((String)command.get("html")).replace("공식 안내","갱신된 공식 안내"));
            var changed=(Map<String,Object>)toolCall(tool,reviewer,command).get("result");
            assertThat(changed.get("resourceId")).isNotEqualTo(resource.toString());
            assertThat(changed.get("approved")).isEqualTo(false);
            assertThat(toolCall(tool,reviewer,review).get("status")).isEqualTo("REJECTED");
            UUID changedId=UUID.fromString((String)changed.get("resourceId"));
            sql.update("update owned_resource_import set expires_at=? where resource_id=?",Timestamp.from(Instant.now().minusSeconds(1)),changedId);
            assertThat(toolCall(tool,reviewer,Map.of("operation","EXPIRE")).get("status")).isEqualTo("OK");
        }
        var output=new java.io.StringWriter();
        assertThat(tool.run("not-a-real-token",new java.io.StringReader("{}"),output)).isEqualTo(1);
        assertThat(output.toString()).doesNotContain("not-a-real-token");
    }
    private Map<String,Object> toolCall(com.disasterinfo.eodiisseo.ai.resource.adapter.GuidanceAdminTool tool,MemberPrincipal actor,Map<String,Object> command)throws Exception{
        String token=app.getBean(TokenService.class).issue(actor.memberId(),actor.sessionId(),Instant.now().plusSeconds(600));
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var out=new java.io.StringWriter();
        tool.run(token,new java.io.StringReader(mapper.writeValueAsString(command)),out);
        return mapper.readValue(out.toString(),Map.class);
    }

    @Test @Order(16) void rainAndConditionallyLinkedFireCompleteThroughLoopbackOllama() throws Exception {
        UUID rain=source("KMA_HEAVY_RAIN"),fire=source("MOIS_DISASTER_MESSAGES");
        var rainRow=Map.<String,Object>of("stnId","108","tmFc","202611011000","tmSeq","55","areaCode","L_E2E","areaName","TEST ONLY",
            "warnVar","2","warnStress","1","command","1","startTime","202611011000","cancel","0");
        var fireRow=Map.<String,Object>of("SN","fire-e2e","DST_SE_NM","산불","CRT_DT","2026-11-01 10:00:00","MSG_CN","TEST ONLY 공식 발생 발표");
        allowIncident(fire,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES,fireRow,"fire-e2e",1,
            com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED,Instant.parse("2026-11-01T01:00:00Z"),null);
        completeWithLoopbackModel(rain,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.HEAVY_RAIN,rainRow);
        completeWithLoopbackModel(fire,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES,fireRow);
    }
    private void completeWithLoopbackModel(UUID source,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider provider,Map<String,Object> row)throws Exception{
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/provider",exchange->{
            byte[] body=fixtureClient(provider,()->List.of(row)).page("test",LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1).raw();
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.createContext("/api/chat",exchange->{
            var request=mapper.readTree(exchange.getRequestBody().readAllBytes());
            var input=mapper.readTree(request.path("messages").get(1).path("content").asString());
            String content=mapper.writeValueAsString(Map.of("excerpts",List.of(input.path("allowedParagraphs").get(0).asString())));
            byte[] bytes=mapper.writeValueAsBytes(Map.of("model","fixture:phase2","done",true,"message",Map.of("role","assistant","content",content)));
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        var settings=new com.disasterinfo.eodiisseo.ai.ollama.OllamaSettings(base,"fixture:phase2",Duration.ofSeconds(5),Duration.ofSeconds(2),Set.of("127.0.0.1"));
        var fake=app.getBean(FakeSummary.class);
        try(var transport=new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp();var model=new com.disasterinfo.eodiisseo.ai.ollama.OllamaChatClient(settings)){
            fake.delegate=new com.disasterinfo.eodiisseo.ai.summary.adapter.OllamaSummaryGenerator(model);
            var client=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->transport.get(base.resolve("/provider"),timeout));
            assertThat(collect(source,client)).isEqualTo(provider==com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES?"RAW_ARCHIVED_CONTRACT_REQUIRED":"SUCCESS");
            String announcement=com.disasterinfo.eodiisseo.disaster.collection.OfficialRecordNormalizer.identity(provider,row);
            UUID disaster=sql.queryForObject("select s.disaster_id from owned_collection_item i join source_disaster s using(source_disaster_id) where i.source_id=? and i.provider_record_key=?",UUID.class,source,announcement);
            UUID revision=revision(disaster);var worker=app.getBean(DisasterRuntimeWorker.class);
            for(int i=0;i<100&&worker.drainOneEvent();i++){}
            assertThat(sql.queryForObject("select status from ai_summary where source_revision_id=?",String.class,revision)).isEqualTo("PENDING");
            for(int i=0;i<100&&worker.drainOneSummary();i++){}
            assertThat(sql.queryForObject("select status from ai_summary where source_revision_id=?",String.class,revision))
                .as("Summary task=%s | Outbox=%s | AI errors=%s",
                    sql.queryForList("select state,last_error_code,attempts from summary_runtime_task where revision_id=?",revision),
                    sql.queryForList("select event_kind,state,last_error_code,attempts from disaster_runtime_outbox where revision_id=?",revision),
                    sql.queryForList("select error_code from service_error_log where component='AISUM' order by occurred_at desc limit 5"))
                .isEqualTo("COMPLETED");
            assertThat(sql.queryForObject("select model_name from ai_summary where source_revision_id=?",String.class,revision)).isEqualTo("fixture:phase2");
            assertThat(http(actor(),"GET","/api/v1/disasters/"+disaster+"/ai-summary",null).body()).contains("COMPLETED");
            assertThat(http(actor(),"GET","/api/v1/disasters/"+disaster,null).body()).contains(disaster.toString());
        }finally{fake.delegate=null;server.stop(0);}
    }

    @Test @Order(17) void expiredRunCannotOverwriteSuccessorCursorOrLease() {
        UUID source=source("MOIS_DISASTER_MESSAGES"),successor=UUID.randomUUID();ready(source);
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES;
        var client=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->{
            sql.update("insert into collection_run(run_id,source_id,mode,started_at,status,trigger_type) values(?,?,'LIVE',?,'RUNNING','MANUAL')",successor,source,Timestamp.from(Instant.now()));
            sql.update("update collection_job_lock set run_id=?,lease_until=? where source_id=?",successor,Timestamp.from(Instant.now().plusSeconds(600)),source);
            sql.update("update owned_collection_cursor set last_failure_code='SUCCESSOR_STATE',next_attempt_at=? where source_id=?",Timestamp.from(Instant.now().plusSeconds(600)),source);
            return new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(200,"{\"header\":{\"resultCode\":\"00\"},\"totalCount\":0,\"body\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        assertThat(collect(source,client)).isEqualTo("LEASE_LOST");
        assertThat(sql.queryForObject("select run_id from collection_job_lock where source_id=?",UUID.class,source)).isEqualTo(successor);
        assertThat(sql.queryForObject("select last_failure_code from owned_collection_cursor where source_id=?",String.class,source)).isEqualTo("SUCCESSOR_STATE");
    }

    @Test @Order(18) void newMigrationPreservesExistingCursorAndPriorChecksums(){
        String database="owned_"+UUID.randomUUID().toString().replace("-","");
        String base=System.getenv("EODIISSEO_AUDIT_JDBC_URL");
        admin.execute("create database "+database);
        try{
            String target=base.substring(0,base.length()-8)+database;
            org.flywaydb.core.Flyway.configure().dataSource(target,"eodiisseo_audit","").locations("classpath:db/migration","classpath:db/owned-runtime")
                .target("2026110303").load().migrate();
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(target,"eodiisseo_audit",""));
            UUID source=UUID.randomUUID();
            jdbc.update("insert into data_source values(?,'TEST UPGRADE','DISASTER','TEST_UPGRADE',5,true)",source);
            jdbc.update("insert into owned_collection_cursor(source_id,completed_through,last_failure_code) values(?,?,'TEST_PRESERVED')",source,java.sql.Date.valueOf("2026-10-31"));
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(target,"eodiisseo_audit","").locations("classpath:db/migration","classpath:db/owned-runtime").load();
            flyway.migrate();flyway.validate();
            assertThat(jdbc.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source)).isEqualTo(java.sql.Date.valueOf("2026-10-31"));
            assertThat(jdbc.queryForObject("select coverage_start from owned_collection_cursor where source_id=?",java.sql.Date.class,source)).isEqualTo(java.sql.Date.valueOf("2026-10-31"));
            assertThat(jdbc.queryForObject("select last_failure_code from owned_collection_cursor where source_id=?",String.class,source)).isEqualTo("TEST_PRESERVED");
        }finally{if(!database.matches("owned_[a-f0-9]{32}"))throw new IllegalStateException();admin.execute("drop database "+database+" with (force)");}
    }

    @Test @Order(19) void historicalOrUndatedSnapshotsDoNotClaimLatestCoverage(){
        var data=app.getBean(OfficialDisasterQueryService.class);
        UUID disaster=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key='incident:synthetic-followup'",UUID.class,SOURCE);
        var representative=data.representative(disaster).orElseThrow();
        var today=LocalDate.ofInstant(Instant.now(),ZoneId.of("Asia/Seoul"));
        UUID run=UUID.randomUUID();
        sql.update("insert into collection_run(run_id,source_id,mode,started_at,finished_at,status,trigger_type) values(?,?,'LIVE',?,?,'SUCCESS','MANUAL')",
            run,SOURCE,Timestamp.from(Instant.now()),Timestamp.from(Instant.now()));
        sql.update("insert into owned_collection_page(page_id,run_id,page_no,raw_body,body_sha256,fetched_at) values(?,?,1,?,?,?)",
            UUID.randomUUID(),run,"TEST FIXTURE".getBytes(java.nio.charset.StandardCharsets.UTF_8),AuthService.hash("TEST FIXTURE"),Timestamp.from(Instant.now()));
        sql.update("insert into owned_collection_coverage(run_id,source_id,from_day,to_day) values(?,?,?,?)",run,SOURCE,java.sql.Date.valueOf(today),java.sql.Date.valueOf(today));
        assertThat(data.latest(representative)).isFalse(); // Current page alone cannot fill the older gap.
        sql.update("update owned_collection_cursor set completed_through=? where source_id=?",java.sql.Date.valueOf(today),SOURCE);
        assertThat(data.latest(representative)).isTrue(); // Explicit complete coverage fixture.
        sql.update("update owned_collection_coverage set from_day=?,to_day=? where run_id=?",java.sql.Date.valueOf(today.minusDays(1)),java.sql.Date.valueOf(today.minusDays(1)),run);
        assertThat(data.latest(representative)).isFalse();
        UUID fire=source("MOIS_DISASTER_MESSAGES");
        assertThat(collect(fire,fixtureClient(com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES,List::of))).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        UUID incident=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key='incident:fire-e2e'",UUID.class,fire);
        assertThat(data.latest(data.representative(incident).orElseThrow())).isFalse();
    }
    @Test @Order(20) void observingOldContentDoesNotRefreshTheNewerRevision(){
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var archived=sql.queryForMap("select canonical_json,content_sha256 from owned_collection_item where source_id=? and provider_record_key='108:202611011230:10' order by first_seen_at asc limit 1",SOURCE);
        var row=mapper.readValue((String)archived.get("canonical_json"),Map.class);
        var stale=Instant.now().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        sql.update("update source_disaster set collected_at=? where source_id=? and source_record_key='incident:fixture-quake-10'",Timestamp.from(stale),SOURCE);
        var client=fixtureClient(com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE,()->List.of(row));
        assertThat(collect(SOURCE,client)).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select collected_at from source_disaster where source_id=? and source_record_key='incident:fixture-quake-10'",Timestamp.class,SOURCE).toInstant()).isEqualTo(stale);
    }


    @Test @Order(21) void documentedCrtDtPreservesCreatedRegisteredModifiedRawWithoutClaimingIncidentOrCoverage(){
        UUID source=source("MOIS_DISASTER_MESSAGES");
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES;
        var row=new LinkedHashMap<String,Object>(Map.of("SN","0000000000000000000301","CRT_DT","2026-10-31 23:59:59",
            "REG_YMD","20261101","MDFCN_YMD","20261102","DST_SE_NM","산불","MSG_CN","SYNTHETIC ONLY 진화 안내","RCPTN_RGN_NM","TEST"));
        var request=new java.util.concurrent.atomic.AtomicReference<URI>();
        var fixture=fixtureClient(provider,()->List.of(row));
        var client=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->{
            request.set(uri);
            return new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(200,fixture.page("TEST",LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1).raw());
        });
        long disasters=sql.queryForObject("select count(*) from disaster",Long.class),events=sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class);
        assertThat(collect(source,client)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        assertThat(request.get().getRawQuery()).contains("crtDt=20261101").doesNotContain("toTmFc","sort");
        row.put("MDFCN_YMD","20261103");row.put("MSG_CN","SYNTHETIC ONLY 수정 안내");
        assertThat(collect(source,client)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        assertThat(collect(source,client)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
        assertThat(sql.queryForObject("select count(*) from owned_collection_item where source_id=? and provider_record_key=?",Long.class,source,row.get("SN"))).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from owned_collection_item where source_id=? and provider_record_key=? and classification='WILDFIRE_MESSAGE_UNLINKED' and source_disaster_id is null",Long.class,source,row.get("SN"))).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from disaster",Long.class)).isEqualTo(disasters);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class)).isEqualTo(events);
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source)).isNull();
        assertThat(sql.queryForObject("select count(*) from owned_collection_coverage where source_id=?",Long.class,source)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"REORDER","DUPLICATE","MODIFIED_DURING_PAGING","TOTAL_CHANGED","INTERRUPTED"})
    @Order(22)
    void messagePageInstabilityCannotAdvanceCoverageOrPartiallyPersist(String mode){
        UUID source=source("MOIS_DISASTER_MESSAGES");
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.WILDFIRE_MESSAGES;
        var counter=new java.util.concurrent.atomic.AtomicInteger();
        var first=Map.<String,Object>of("SN","phase3-"+mode+"-A","CRT_DT","2026-11-01 10:00:00","MDFCN_YMD","20261101","DST_SE_NM","산불","MSG_CN","SYNTHETIC");
        var second=new LinkedHashMap<String,Object>(first);second.put("SN","phase3-"+mode+"-B");
        long pages=sql.queryForObject("select count(*) from owned_collection_page",Long.class),items=sql.queryForObject("select count(*) from owned_collection_item",Long.class);
        var client=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->{
            int page=counter.incrementAndGet();
            if(page==2&&mode.equals("INTERRUPTED"))throw new com.disasterinfo.eodiisseo.disaster.collection.CollectionFailure("TIMEOUT");
            Map<String,Object> current=page==1?first:second;
            if(page==2&&mode.equals("DUPLICATE"))current=first;
            if(page==2&&mode.equals("MODIFIED_DURING_PAGING")){second.put("SN",first.get("SN"));second.put("MDFCN_YMD","20261102");current=second;}
            return new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(200,tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsBytes(Map.of("header",Map.of("resultCode","00"),"totalCount",page==2&&mode.equals("TOTAL_CHANGED")?3:2,
                    "pageNo",page,"body",List.of(current))));
        });
        String result=collect(source,client);
        if(mode.equals("REORDER")){
            assertThat(result).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
            var reverse=fixtureClient(provider,()->List.of(second,first));
            assertThat(collect(source,reverse)).isEqualTo("RAW_ARCHIVED_CONTRACT_REQUIRED");
            assertThat(sql.queryForObject("select count(*) from owned_collection_item",Long.class)).isEqualTo(items+2);
        }else{
            assertThat(result).isEqualTo(mode.equals("INTERRUPTED")?"TIMEOUT":"PAGING_CHANGED");
            assertThat(sql.queryForObject("select count(*) from owned_collection_page",Long.class)).isEqualTo(pages);
            assertThat(sql.queryForObject("select count(*) from owned_collection_item",Long.class)).isEqualTo(items);
        }
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source)).isNull();
        assertThat(sql.queryForObject("select count(*) from owned_collection_coverage where source_id=?",Long.class,source)).isZero();
    }

    @Test @Order(23) void liftedWarningKeepsHistoricalImpactWhileExpiredOrIncompleteCrosswalkIsUnknown(){
        UUID source=source("KMA_HEAVY_RAIN");
        UUID disaster=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key like 'L_PHASE2:%'",UUID.class,source);
        var reader=app.getBean(com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader.class);
        var mappings=app.getBean(FixtureMappings.class).mappings;
        Instant issued=sql.queryForObject("select source_updated_at from source_disaster where disaster_id=?",Timestamp.class,disaster).toInstant();
        Instant end=Instant.now().minusSeconds(1);
        var old=new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping("L_PHASE2",Set.of("TEST_EXACT"),
            URI.create("https://www.weather.go.kr/test-only-not-real-crosswalk"),"SYNTHETIC-V1",issued,end,true);
        mappings.put("L_PHASE2",old);
        assertThat(reader.find(disaster,issued).exactAdminAreaCodes()).containsExactly("TEST_EXACT");
        assertThat(reader.find(disaster,end.minusNanos(1)).activeIncident()).isFalse();
        assertThat(reader.find(disaster,end.minusNanos(1)).exactAdminAreaCodes()).containsExactly("TEST_EXACT");
        assertThat(reader.find(disaster,end).knowledge()).isEqualTo(com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader.Knowledge.UNKNOWN);
        sql.update("insert into admin_area(area_code,name,level,active) values('TEST_SPLIT','SYNTHETIC SPLIT','SIGUNGU',true)");
        mappings.put("L_PHASE2",new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping("L_PHASE2",
            Set.of("TEST_EXACT","TEST_SPLIT"),old.evidenceUrl(),"SYNTHETIC-V2",issued,Instant.now().plusSeconds(600),false));
        assertThat(reader.find(disaster,Instant.now()).knowledge()).isEqualTo(com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader.Knowledge.UNKNOWN);
        mappings.put("L_PHASE2",new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping("L_PHASE2",
            Set.of("TEST_EXACT","TEST_SPLIT"),old.evidenceUrl(),"SYNTHETIC-V2",issued,Instant.now().plusSeconds(600),true));
        assertThat(reader.find(disaster,Instant.now()).exactAdminAreaCodes()).containsExactlyInAnyOrder("TEST_EXACT","TEST_SPLIT");
        mappings.remove("L_PHASE2");
        assertThat(reader.find(disaster,Instant.now()).knowledge()).isEqualTo(com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader.Knowledge.UNKNOWN);
    }


    @Test @Order(24) void officialReannouncementTypeAndReferenceAloneDoNotMergeEarthquakes(){
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE;
        var first=Map.<String,Object>of("stnId","108","tmFc","202611011200","tmSeq","81","cnt","1","fcTp","3",
            "tmEqk","20261101115900","lat","37.1","lon","127.1","loc","SYNTHETIC");
        var followup=new LinkedHashMap<String,Object>(first);followup.put("fcTp","5");followup.put("tmSeq","82");followup.put("tmFc","202611011300");
        long count=sql.queryForObject("select count(*) from disaster",Long.class);
        assertThat(collect(SOURCE,fixtureClient(provider,()->List.of(first,followup)))).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select count(*) from disaster",Long.class)).isEqualTo(count);
        assertThat(sql.queryForObject("select count(*) from owned_collection_item where source_id=? and provider_record_key in('108:202611011200:81','108:202611011300:82') and classification='EARTHQUAKE_UNLINKED'",Long.class,SOURCE)).isEqualTo(2);
    }
    @Test @Order(25) void officialMajorRainWarningStaysSourceLevelWithoutInventedCommonSeverity(){
        UUID source=source("KMA_HEAVY_RAIN");
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.HEAVY_RAIN;
        var row=new LinkedHashMap<String,Object>(Map.of("stnId","108","tmFc","202611011000","tmSeq","90","areaCode","L_MAJOR","areaName","SYNTHETIC",
            "warnVar","2","warnStress","0","command","1","startTime","202611011000","cancel","0"));
        var client=fixtureClient(provider,()->List.of(row));
        assertThat(collect(source,client)).isEqualTo("SUCCESS");
        UUID disaster=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key like 'L_MAJOR:%'",UUID.class,source);
        row.put("warnStress","2");row.put("command","7");row.put("tmFc","202611011100");
        assertThat(collect(source,client)).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select source_status from source_disaster where disaster_id=?",String.class,disaster)).isEqualTo("MAJOR_WARNING:7");
        assertThat(sql.queryForObject("select title from disaster where disaster_id=?",String.class,disaster)).contains("중대경보").doesNotContain("주의보");
        assertThat(sql.queryForObject("select official_severity_id from disaster where disaster_id=?",UUID.class,disaster)).isNull();
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=? and event_kind='SEVERITY_INCREASE'",Long.class,disaster)).isZero();
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id) where disaster_id=?",Long.class,disaster)).isEqualTo(2);
        assertThat(collect(source,client)).isEqualTo("SUCCESS");
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id) where disaster_id=?",Long.class,disaster)).isEqualTo(2);
    }

    @Test @Order(26) void noDataObservationIsDistinctAndNeverAdvancesCoverage(){
        UUID source=SOURCE;ready(source);
        long coverageBefore=sql.queryForObject("select count(*) from owned_collection_coverage where source_id=?",Long.class,source);
        long recordsBefore=sql.queryForObject("select count(*) from source_disaster where source_id=?",Long.class,source);
        java.sql.Date completedBefore=sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source);
        var client=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(
            com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE,
            (uri,timeout)->new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(200,
                "{\"response\":{\"header\":{\"resultCode\":\"03\",\"resultMsg\":\"NODATA_ERROR\"}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var day=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        var result=app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionService.class)
            .collect(source,client,"synthetic-key",day,day,"owned-disaster-development");
        assertThat(result.status()).isEqualTo("NO_DATA");assertThat(result.received()).isZero();assertThat(result.normalized()).isZero();
        assertThat(sql.queryForObject("select error_code from collection_run where source_id=? and error_code='NO_DATA'",String.class,source)).isEqualTo("NO_DATA");
        assertThat(sql.queryForObject("select count(*) from owned_collection_coverage where source_id=?",Long.class,source)).isEqualTo(coverageBefore);
        assertThat(sql.queryForObject("select completed_through from owned_collection_cursor where source_id=?",java.sql.Date.class,source)).isEqualTo(completedBefore);
        assertThat(sql.queryForObject("select count(*) from source_disaster where source_id=?",Long.class,source)).isEqualTo(recordsBefore);
    }

    @Test @Order(27) void inconsistentOfficialTotalNeverInventsPaginationOrCoverage(){
        var source=SOURCE;ready(source);
        long pages=sql.queryForObject("select count(*) from owned_collection_page",Long.class);
        long coverage=sql.queryForObject("select count(*) from owned_collection_coverage",Long.class);
        long events=sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class);
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE;
        var client=new com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient(provider,(uri,timeout)->
            new com.disasterinfo.eodiisseo.disaster.collection.OfficialHttp.Reply(200,
            "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"pageNo\":1,\"totalCount\":0,\"items\":{\"item\":[{\"fixture\":\"SYNTHETIC\"}]}}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var day=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        var outcome=app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialCollectionService.class)
            .collect(source,client,"synthetic-key",day,day,"owned-disaster-development");
        assertThat(outcome.status()).isEqualTo("PAGE_TOTAL_INCONSISTENT");
        assertThat(sql.queryForObject("select count(*) from owned_collection_page",Long.class)).isEqualTo(pages);
        assertThat(sql.queryForObject("select count(*) from owned_collection_coverage",Long.class)).isEqualTo(coverage);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class)).isEqualTo(events);
    }
    @Test @Order(28) void realSourceMetadataPlansRequireOpsButNeverCreateOrApproveDocuments() throws Exception {
        var tool=app.getBean(com.disasterinfo.eodiisseo.ai.resource.adapter.GuidanceAdminTool.class);
        var reviewer=actor();var ordinary=actor();
        sql.update("insert into member_role values(?,'OPS',?)",reviewer.memberId(),Timestamp.from(Instant.now()));
        long documents=sql.queryForObject("select count(*) from official_resource",Long.class);
        long reviews=sql.queryForObject("select count(*) from ai_resource_review",Long.class);
        var root=tools.jackson.databind.json.JsonMapper.builder().build().readValue(java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/disasterinfo/eodiisseo/ai/resource/PHASE6_SOURCE_CATALOG.json")),Map.class);
        for(var source:(List<Map<String,Object>>)root.get("sources")){
            assertThat(toolCall(tool,ordinary,source).get("status")).isEqualTo("REJECTED");
            var response=toolCall(tool,reviewer,source);assertThat(response.get("status")).isEqualTo("OK");
            var metadata=(Map<String,Object>)response.get("result");
            assertThat(metadata.get("approvalState")).isEqualTo("PENDING");
            assertThat(metadata.get("runtimeSearchEligible")).isEqualTo(false);
            assertThat(metadata.get("contentFingerprint")).isNull();
        }
        assertThat(sql.queryForObject("select count(*) from official_resource",Long.class)).isEqualTo(documents);
        assertThat(sql.queryForObject("select count(*) from ai_resource_review",Long.class)).isEqualTo(reviews);
    }
    @Test @Order(29) void syntheticAccessiblePosterRetainsExactReviewAndRevokesAfterAltChanges() throws Exception {
        var tool=app.getBean(com.disasterinfo.eodiisseo.ai.resource.adapter.GuidanceAdminTool.class);
        var reviewer=actor();sql.update("insert into member_role values(?,'OPS',?)",reviewer.memberId(),Timestamp.from(Instant.now()));
        String paragraph="TEST ONLY 산불 대피 방법은 테스트 안내를 확인합니다.";
        var input=new LinkedHashMap<String,Object>(Map.of("operation","IMPORT","hazard","WILDFIRE",
            "sourceUrl","https://www.mois.go.kr/phase6-synthetic-accessible-poster","title","TEST ONLY 산불 대피 행동요령",
            "bodyElementId","guide","html","<div id='guide'><img alt='"+paragraph+"'></div>",
            "expiresAt",Instant.now().plusSeconds(600).toString()));
        var imported=(Map<String,Object>)toolCall(tool,reviewer,input).get("result");
        UUID id=UUID.fromString(imported.get("resourceId").toString());
        assertThat(imported.get("approved")).isEqualTo(false);
        var search=app.getBean(com.disasterinfo.eodiisseo.ai.resource.ResourceService.class);
        assertThat(search.search("산불 대피 방법").resources()).noneMatch(r->r.resourceId().equals(id));
        var command=Map.<String,Object>of("operation","APPROVE","resourceId",id.toString(),"expectedFingerprint",imported.get("fingerprint"),
            "validUntil",Instant.now().plusSeconds(300).toString(),"evidenceReference","SYNTHETIC HUMAN REVIEW ONLY; NOT AN OPERATIONAL APPROVAL",
            "rightsAndExternalAiUseConfirmed",true,"injectionReviewed",true);
        assertThat(toolCall(tool,reviewer,command).get("status")).isEqualTo("OK");
        assertThat(search.search("산불 대피 방법").resources()).anySatisfy(r->{
            assertThat(r.resourceId()).isEqualTo(id);assertThat(r.content()).isEqualTo(paragraph);
        });
        input.put("html","<div id='guide'><img alt='"+paragraph+" TEST CHANGED'></div>");
        var changed=(Map<String,Object>)toolCall(tool,reviewer,input).get("result");
        assertThat(changed.get("resourceId")).isNotEqualTo(id.toString());
        assertThat(changed.get("approved")).isEqualTo(false);
        assertThat(search.search("산불 대피 방법").resources()).noneMatch(r->r.resourceId().equals(id));
    }

    @Test @Order(30)
    @EnabledIfEnvironmentVariable(named="EODIISSEO_RAG_REAL_DATA_VALIDATION",matches="true")
    void realOfficialSourcesImportInspectAndRemainUnapproved() throws Exception {
        var directory=java.nio.file.Path.of(System.getenv("EODIISSEO_RAG_REAL_EXPORT_DIRECTORY")).toAbsolutePath().normalize();
        if(!directory.toString().equals("C:\\Users\\dkemf\\AppData\\Local\\Temp\\eodiisseo-phase7-docs-20261104"))
            throw new IllegalArgumentException("Only the inspected temporary export directory is allowed");
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var manifest=mapper.readValue(java.nio.file.Files.readString(directory.resolve("manifest.json")),Map.class);
        var tool=app.getBean(com.disasterinfo.eodiisseo.ai.resource.adapter.GuidanceAdminTool.class);
        var reviewer=actor();sql.update("insert into member_role values(?,'OPS',?)",reviewer.memberId(),Timestamp.from(Instant.now()));
        var store=app.getBean(com.disasterinfo.eodiisseo.ai.resource.port.OfficialResourceStore.class);
        var approval=app.getBean(com.disasterinfo.eodiisseo.ai.resource.adapter.DatabaseResourceApproval.class);
        var search=app.getBean(com.disasterinfo.eodiisseo.ai.resource.ResourceService.class);
        var paragraphs=app.getBean(com.disasterinfo.eodiisseo.ai.resource.port.ResourceParagraphSearch.class);
        var reviewNotes=new StringBuilder("\n\n## 개발 DB IMPORT/INSPECT 결과\n실제 APPROVE는 수행하지 않았습니다. 아래 ID는 종료 후 폐기되는 DB의 검수 식별자이며 운영 승인에는 새 INSPECT가 필요합니다.\n");
        int registered=0;
        for(var source:(List<Map<String,Object>>)manifest.get("sources")){
            String filename=source.get("exportFile").toString();
            if(!filename.matches("(rain|wildfire)-export\\.html"))throw new IllegalArgumentException("Only inspected exports");
            byte[] raw=java.nio.file.Files.readAllBytes(directory.resolve(filename));
            String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
            assertThat(sha).isEqualTo(source.get("exportSha256"));
            assertThat(source.get("rightsState")).isEqualTo("TYPE1_SCOPED_VERIFIED");
            var plans=mapper.readValue(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/disasterinfo/eodiisseo/ai/resource/PHASE7_SOURCE_CATALOG.json")),Map.class);
            var plan=((List<Map<String,Object>>)plans.get("sources")).stream().filter(v->v.get("sourceUrl").equals(source.get("sourceUrl"))).findFirst().orElseThrow();
            var planned=toolCall(tool,reviewer,plan);assertThat(planned.get("status")).isEqualTo("OK");
            assertThat(((Map<?,?>)planned.get("result")).get("runtimeSearchEligible")).isEqualTo(false);
            var request=Map.<String,Object>of("operation","IMPORT","hazard",source.get("hazard"),"sourceUrl",source.get("sourceUrl"),
                "title",source.get("title"),"bodyElementId","guide","html",new String(raw,java.nio.charset.StandardCharsets.UTF_8),
                "expiresAt",Instant.now().plusSeconds(3600).toString());
            var imported=toolCall(tool,reviewer,request);assertThat(imported.get("status")).isEqualTo("OK");
            var inspected=(Map<String,Object>)imported.get("result");
            UUID id=UUID.fromString(inspected.get("resourceId").toString());
            var doc=store.findById(id).orElseThrow();
            String expected=((List<String>)source.get("paragraphs")).stream().map(t->t.replaceAll("\\s+"," ").strip()).collect(java.util.stream.Collectors.joining("\n\n"));
            assertThat(doc.getContentText().equals(expected)).as("Inspected complete source paragraphs are preserved").isTrue();
            assertThat(doc.getAgencyName()).isEqualTo(source.get("publisher"));
            assertThat(approval.findApproval(id)).isEmpty();
            assertThat(inspected.get("approved")).isEqualTo(false);
            byte[] stored=sql.queryForObject("select raw_body from owned_resource_import where resource_id=?",byte[].class,id);
            assertThat(Arrays.equals(raw,stored)).as("Exact inspected export bytes persisted").isTrue();
            String canonical=source.get("hazard").equals("HEAVY_RAIN")?"호우 지하차도 대피 방법":"산불 대피 방법";
            var query=paragraphs.prepare(canonical).orElseThrow();
            int preview=paragraphs.select(query,doc).size();
            assertThat(preview).as("Offline suitability, without approval bypass").isGreaterThan(0);
            assertThat(search.search(canonical).resources()).isEmpty();
            List<String> questions=source.get("hazard").equals("HEAVY_RAIN")?
                List.of("호우가 심한데 지하차도로 이동해도 돼?","집중호우가 발생했을 때 지하 공간에 있다면 어떻게 해야 해?"):
                List.of("산불 대피 안내가 내려오면 무엇을 준비해야 해?","산불이 가까워졌는데 어느 방향으로 이동해야 해?");
            List<Integer> counts=new ArrayList<>();
            for(String question:questions){
                var prepared=paragraphs.prepare(question);
                counts.add(prepared.map(q->paragraphs.select(q,doc).size()).orElse(0));
                assertThat(search.search(question).resources()).isEmpty();
            }
            long before=sql.queryForObject("select count(*) from official_resource",Long.class);
            var importer=app.getBean(com.disasterinfo.eodiisseo.ai.resource.adapter.OfficialGuidanceImporter.class);
            tx.executeWithoutResult(status->{
                importer.publish(reviewer,source.get("sourceUrl").toString(),source.get("title").toString(),"guide",
                    (new String(raw,java.nio.charset.StandardCharsets.UTF_8)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),Instant.now().plusSeconds(3600));
                status.setRollbackOnly();
            });
            assertThat(sql.queryForObject("select count(*) from official_resource",Long.class)).isEqualTo(before);
            assertThat(store.findById(id).orElseThrow().revisionFingerprint()).isEqualTo(doc.revisionFingerprint());
            System.out.println("RAG_REAL_REVIEW hazard="+source.get("hazard")+" publisher="+source.get("publisher")+
                " stored=1 paragraphs="+((List<?>)source.get("paragraphs")).size()+" canonical_preview="+preview+" example_previews="+counts+" approved=0 runtime_results=0 rollback=OK");
            reviewNotes.append("\n### ").append(source.get("hazard")).append("\nresourceId: ").append(id)
                .append("\nrevisionFingerprint: ").append(doc.revisionFingerprint()).append("\nexport SHA256: ").append(sha)
                .append("\npreview count: ").append(preview).append("\nprovided question previews: ").append(counts)
                .append("\nstatus: PENDING / approved corpus search not performed\n");
            registered++;
        }
        assertThat(registered).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from ai_resource_review",Long.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class)).isZero();
        java.nio.file.Files.writeString(directory.resolve("PHASE7_RAG_REVIEW_PACKET.md"),reviewNotes.toString(),
            java.nio.file.StandardOpenOption.APPEND);
    }

    @Test @Order(31)
    @EnabledIfEnvironmentVariable(named="EODIISSEO_RAG_HUMAN_REVIEW_SCOPE",matches="2026-11-06-two-reviewed-exports-dev-search-only")
    void humanReviewedRealExportsApproveSearchAndRevokeOnlyInDisposableDatabase() throws Exception {
        // Human authorisation: 2026-11-06 user reply. Search in this disposable DB only; no model transmission.
        Path directory=Path.of("C:\\Users\\dkemf\\AppData\\Local\\Temp\\eodiisseo-phase7-docs-20261104");
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var manifest=mapper.readValue(Files.readString(directory.resolve("manifest.json")),Map.class);
        var sources=(List<Map<String,Object>>)manifest.get("sources");
        assertThat(sources).hasSize(2);
        var expected=Map.of(
            "HEAVY_RAIN",List.of("rain-export.html","4f207f9218c419ec844b50261d53abffe2aa8eb39b20efb038537ad817a6c17b",
                "https://nonsan.go.kr/kor/html/sub05/050610.html?mode=V&no=b3a79c4a1274bdfe06e9cd3418efd51f",
                "논산시","여름철 자연재난(호우·태풍 등) 행동요령 (행정안전부 표기·논산시 게시)"),
            "WILDFIRE",List.of("wildfire-export.html","96d2f4fe1f39a006cc36f66ec30417ca8215649dd4d81e0ac15c8f0d9d4f99fa",
                "https://www.rda.go.kr/board/board.do?dataNo=100000801828&mode=view&prgId=con_cardnews",
                "농촌진흥청","산불 국민행동요령 (산림청 표기·농촌진흥청 게시)"));
        var seen=new HashSet<String>();
        // Validate BOTH approved exports and metadata before importing or granting any review.
        for(var source:sources){
            String hazard=source.get("hazard").toString();
            assertThat(seen.add(hazard)).isTrue();
            assertThat(expected).containsKey(hazard);
            var pin=expected.get(hazard);
            assertThat(List.of(source.get("exportFile"),source.get("exportSha256"),source.get("sourceUrl"),
                source.get("publisher"),source.get("title"))).isEqualTo(pin);
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(directory.resolve(pin.get(0)))));
            assertThat(hash).isEqualTo(pin.get(1));
        }
        var tool=app.getBean(GuidanceAdminTool.class);
        var reviewer=actor();var ordinary=actor();
        sql.update("insert into member_role values(?,'OPS',?)",reviewer.memberId(),Timestamp.from(Instant.now()));
        var store=app.getBean(com.disasterinfo.eodiisseo.ai.resource.port.OfficialResourceStore.class);
        var search=app.getBean(com.disasterinfo.eodiisseo.ai.resource.ResourceService.class);
        long reviewsBefore=sql.queryForObject("select count(*) from ai_resource_review",Long.class);
        long outboxBefore=sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class);
        var notes=new StringBuilder("\n\n## 2026-11-06 명시적 사용자 승인 후 개발 DB 검색\n운영 승인/외부 AI 전송 없음. 동일 export만 재등록. 검증 후 승인 철회 및 DB 폐기.\n");
        for(var source:sources){
            var pin=expected.get(source.get("hazard"));
            byte[] raw=Files.readAllBytes(directory.resolve(pin.get(0)));
            var imported=toolCall(tool,reviewer,Map.of("operation","IMPORT","hazard",source.get("hazard"),
                "sourceUrl",pin.get(2),"title",pin.get(4),"bodyElementId","guide",
                "html",new String(raw,java.nio.charset.StandardCharsets.UTF_8),
                "expiresAt",Instant.now().plusSeconds(1800).toString()));
            assertThat(imported.get("status")).isEqualTo("OK");
            var initial=(Map<String,Object>)imported.get("result");
            UUID id=UUID.fromString(initial.get("resourceId").toString());
            var inspectedResponse=toolCall(tool,reviewer,Map.of("operation","INSPECT","resourceId",id.toString()));
            assertThat(inspectedResponse.get("status")).isEqualTo("OK");
            var inspected=(Map<String,Object>)inspectedResponse.get("result");
            var doc=store.findById(id).orElseThrow();
            assertThat(inspected.get("fingerprint")).isEqualTo(doc.revisionFingerprint());
            assertThat(inspected.get("approved")).isEqualTo(false);
            String canonical=source.get("hazard").equals("HEAVY_RAIN")?"호우 지하차도 대피 방법":"산불 대피 방법";
            assertThat(search.search(canonical).resources()).noneMatch(r->r.resourceId().equals(id));
            var command=new LinkedHashMap<String,Object>(Map.of("operation","APPROVE","resourceId",id.toString(),
                "expectedFingerprint",inspected.get("fingerprint"),"validUntil",Instant.now().plusSeconds(600).toString(),
                "evidenceReference","USER REVIEW 2026-11-06; TEMP DEVELOPMENT SEARCH ONLY; export SHA256="+pin.get(1),
                "rightsAndExternalAiUseConfirmed",true,"injectionReviewed",true));
            assertThat(toolCall(tool,ordinary,command).get("status")).isEqualTo("REJECTED");
            command.put("expectedFingerprint","unreviewed-revision");
            assertThat(toolCall(tool,reviewer,command).get("status")).isEqualTo("REJECTED");
            command.put("expectedFingerprint",inspected.get("fingerprint"));
            var approved=toolCall(tool,reviewer,command);
            assertThat(approved.get("status")).isEqualTo("OK");
            assertThat(((Map<?,?>)approved.get("result")).get("approved")).isEqualTo(true);
            List<String> questions=source.get("hazard").equals("HEAVY_RAIN")?
                List.of(canonical,"호우가 심한데 지하차도로 이동해도 돼?","집중호우가 발생했을 때 지하 공간에 있다면 어떻게 해야 해?"):
                List.of(canonical);
            var counts=new ArrayList<Integer>();
            for(String question:questions){
                var result=search.search(question);
                assertThat(result.sufficientEvidence()).isTrue();
                assertThat(result.resources()).hasSize(1);
                var resource=result.resources().getFirst();
                assertThat(resource.resourceId()).isEqualTo(id);
                assertThat(resource.organization()).isEqualTo(pin.get(3));
                assertThat(resource.title()).isEqualTo(pin.get(4));
                assertThat(resource.sourceUrl().toString()).isEqualTo(pin.get(2));
                assertThat(resource.usableAsEvidence()).isTrue();
                var complete=List.of(doc.getContentText().split("\\n\\n"));
                var selected=List.of(resource.content().split("\\n\\n"));
                assertThat(complete.containsAll(selected)).as("Only complete verbatim inspected paragraphs").isTrue();
                counts.add(selected.size());
            }
            for(String unsupported:List.of("산불 대피 안내가 내려오면 무엇을 준비해야 해?","산불이 가까워졌는데 어느 방향으로 이동해야 해?",
                "지진이 발생해서 건물이 흔들리는 동안 어떻게 해야 해?","엘리베이터 안에서 지진이 발생하면 어떻게 해야 해?")){
                assertThat(search.search(unsupported).resources()).isEmpty();
                assertThat(search.search(unsupported).sufficientEvidence()).isFalse();
            }
            assertThat(toolCall(tool,reviewer,Map.of("operation","REVOKE","resourceId",id.toString())).get("status")).isEqualTo("OK");
            for(String question:questions)assertThat(search.search(question).resources()).noneMatch(r->r.resourceId().equals(id));
            notes.append("\n").append(source.get("hazard")).append(": export=").append(pin.get(1))
                .append("; resourceId=").append(id).append("; revision=").append(doc.revisionFingerprint())
                .append("; approved search paragraph counts=").append(counts).append("; revoked=OK\n");
            System.out.println("RAG_HUMAN_REVIEW hazard="+source.get("hazard")+" approved_search_paragraphs="+counts+" source_exact=true unapproved_blocked=true wrong_revision_blocked=true unauthorized_blocked=true revoked=OK");
        }
        assertThat(sql.queryForObject("select count(*) from ai_resource_review",Long.class)).isEqualTo(reviewsBefore+2);
        assertThat(sql.queryForObject("select count(*) from ai_resource_review where revoked_at is null",Long.class)).isZero();
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class)).isEqualTo(outboxBefore);
        Files.writeString(directory.resolve("PHASE7_RAG_REVIEW_PACKET.md"),notes.toString(),StandardOpenOption.APPEND);
    }

    @Test @Order(32)
    void earthquakeHeadAndEventsRequireAnActuallySavedNewRevision() {
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.EARTHQUAKE;
        var state=com.disasterinfo.eodiisseo.disaster.port.VerifiedIncidentLinkPort.State.OCCURRED;
        var occurred=Instant.parse("2026-11-01T03:00:00Z");
        String incident="revision-result-"+UUID.randomUUID(),key="incident:"+incident;
        var row=new LinkedHashMap<String,Object>(Map.of("stnId","108","tmFc","202611011230","tmSeq","65",
            "tmEqk","20261101120000","lat","37.1","lon","127.1","loc","TEST WRITE RESULT"));
        var service=app.getBean(VerifiedIncidentService.class);
        java.util.function.Supplier<com.disasterinfo.eodiisseo.disaster.collection.OfficialRecordNormalizer.Result> normalize=()->
            service.normalize(SOURCE,provider,row,
                com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(row),Instant.now());
        allowIncident(SOURCE,provider,row,incident,1,state,occurred,null);
        assertThat(normalize.get().classification()).isEqualTo("EARTHQUAKE_LINKED");
        UUID disaster=sql.queryForObject("select disaster_id from source_disaster where source_id=? and source_record_key=?",UUID.class,SOURCE,key);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=?",Long.class,disaster)).isEqualTo(2);
        var initial=earthquakeWriteSnapshot(incident,disaster);
        assertThat(normalize.get().classification()).isEqualTo("INCIDENT_DUPLICATE");
        assertThat(earthquakeWriteSnapshot(incident,disaster)).isEqualTo(initial);

        row.put("tmFc","202611011330");row.put("tmSeq","66");row.put("cor","TEST CORRECTION");
        for(String invalid:List.of("NaN","91","invalid")){
            row.put("lat",invalid);
            allowIncident(SOURCE,provider,row,incident,2,state,occurred,null);
            assertThat(normalize.get().classification()).isEqualTo("INVALID_FACTS");
            assertThat(earthquakeWriteSnapshot(incident,disaster)).isEqualTo(initial);
        }
        row.remove("lat");
        allowIncident(SOURCE,provider,row,incident,2,state,occurred,null);
        assertThat(normalize.get().classification()).isEqualTo("INVALID_FACTS");
        assertThat(earthquakeWriteSnapshot(incident,disaster)).isEqualTo(initial);

        row.put("lat","37.2");
        allowIncident(SOURCE,provider,row,incident,2,state,occurred,null);
        assertThat(normalize.get().classification()).isEqualTo("EARTHQUAKE_LINKED");
        assertThat(sql.queryForObject("select official_sequence from owned_incident_head where source_id=? and incident_key=?",Long.class,SOURCE,incident)).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=? and event_kind='NEW'",Long.class,disaster)).isEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=? and event_kind='REVISION'",Long.class,disaster)).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.disaster_id=?",Long.class,disaster)).isEqualTo(2);
        var corrected=earthquakeWriteSnapshot(incident,disaster);
        assertThat(normalize.get().classification()).isEqualTo("INCIDENT_DUPLICATE");
        assertThat(earthquakeWriteSnapshot(incident,disaster)).isEqualTo(corrected);

        // A real PostgreSQL write failure after revision/outbox/head writes must roll ALL of them back.
        row.put("tmFc","202611011430");row.put("tmSeq","67");row.put("lat","37.3");
        allowIncident(SOURCE,provider,row,incident,3,state,occurred,null);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{
            assertThat(normalize.get().classification()).isEqualTo("EARTHQUAKE_LINKED");
            sql.update("insert into owned_incident_head select * from owned_incident_head where source_id=? and incident_key=?",SOURCE,incident);
        })).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(earthquakeWriteSnapshot(incident,disaster)).isEqualTo(corrected);

        // A legacy row with identical content has no NEW revision and must not become a verified head/event.
        String unchanged="unchanged-"+UUID.randomUUID(),unchangedKey="incident:"+unchanged;
        String original=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(row);
        app.getBean(DisasterService.class).earthquake(SOURCE,unchangedKey,row,original,Instant.now());
        long events=sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class);
        allowIncident(SOURCE,provider,row,unchanged,1,state,occurred,null);
        assertThat(normalize.get().classification()).isEqualTo("INVALID_FACTS");
        assertThat(sql.queryForObject("select count(*) from owned_incident_head where source_id=? and incident_key=?",Long.class,SOURCE,unchanged)).isZero();
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox",Long.class)).isEqualTo(events);
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision r join source_disaster s using(source_disaster_id) where s.source_id=? and s.source_record_key=?",Long.class,SOURCE,unchangedKey)).isEqualTo(1);
    }

    private Map<String,Object> earthquakeWriteSnapshot(String incident,UUID disaster) {
        return Map.of(
            "head",sql.queryForMap("select * from owned_incident_head where source_id=? and incident_key=?",SOURCE,incident),
            "source",sql.queryForMap("select * from source_disaster where disaster_id=?",disaster),
            "disaster",sql.queryForMap("select * from disaster where disaster_id=?",disaster),
            "revisions",sql.queryForList("select * from source_disaster_revision where source_disaster_id in (select source_disaster_id from source_disaster where disaster_id=?) order by revision_no",disaster),
            "events",sql.queryForList("select * from disaster_runtime_outbox where disaster_id=? order by event_key",disaster));
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Providers {
        @Bean @Primary SocialIdentityVerifier fixtureSocial() {
            var verifier=mock(SocialIdentityVerifier.class);
            when(verifier.verify(anyString(),anyString())).thenAnswer(call->new SocialIdentityVerifier.Identity(call.getArgument(0),call.getArgument(1),"TEST MEMBER"));
            return verifier;
        }
        @Bean FixtureLinks fixtureLinks(){return new FixtureLinks();}
        @Bean FixtureMappings fixtureMappings(){return new FixtureMappings();}
        @Bean FakeSummary fakeSummary(){return new FakeSummary();}
        @Bean FakeChat fakeChat(){return new FakeChat();}
    }
    static class FakeSummary implements SummaryGenerator {
        volatile SummaryGenerator delegate;volatile boolean enabled=true;
        public boolean isConfigured(){return enabled;}
        public Generated generate(OfficialRevision revision){if(delegate!=null)return delegate.generate(revision);return new Generated("TEST SUMMARY","test-model",Instant.now());}
    }
    static class FakeChat implements ChatAnswerGenerationPort {
        volatile String lastQuestion;
        public boolean isConfigured(){return true;}
        public GeneratedAnswer generate(GenerationRequest request){
            lastQuestion=request.question();
            return new GeneratedAnswer(request.evidence().getFirst().content(),List.of(request.evidence().getFirst().resourceId()),true,"test-chat");
        }
    }
    /** Audit reproduction of team-owned relevance; true SOURCE_ADDRESS is a known defect, not approval of that policy. */
    @Test @Order(100) void c1AreaRelevanceAuditExposesSourceAddressFallbackAndAncestorOnlyTraversal(){
        UUID disaster=write("c1-area-"+UUID.randomUUID(),"first");var member=actor();
        UUID source=sql.queryForObject("select source_disaster_id from source_disaster where disaster_id=?",UUID.class,disaster);
        tx.executeWithoutResult(status->{
            String root="C1_"+UUID.randomUUID().toString().substring(0,8),city=root+"C",dong=root+"D",other=root+"X";
            sql.update("insert into admin_area(area_code,name,level,active) values(?,'SYNTHETIC PROVINCE','SIDO',true),(?,'SYNTHETIC OTHER','SIDO',true)",root,other);
            sql.update("insert into admin_area(area_code,name,level,parent_area_code,active) values(?,'SYNTHETIC CITY','SIGUNGU',?,true),(?,'SYNTHETIC DONG','EUPMYEONDONG',?,true)",city,root,dong,city);
            var interests=app.getBean(InterestAreas.class);var queries=app.getBean(OfficialDisasterQueryService.class);
            var subscribed=new LinkedHashMap<String,UUID>();
            for(String code:List.of(root,city,dong,other)){UUID id=UUID.randomUUID();subscribed.put(code,id);sql.update("insert into interest_area values(?,?,?,'SYNTHETIC',true,now())",id,member.memberId(),code);}
            Store notificationStore=app.getBean(Store.class);
            notificationStore.initializeSettings(member.memberId(),Instant.now());
            notificationStore.saveSettings(member.memberId(),new Settings(true,null,true,Instant.now()));
            app.getBean(NotificationService.class).registerDevice(new MemberSession(member.memberId(),member.sessionId()),"c1-"+member.memberId(),new com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.DeviceRequest("SYNTHETIC-C1-TOKEN-"+member.memberId(),"ANDROID"));
            var sent=new java.util.concurrent.atomic.AtomicInteger();
            FcmSender fakeSender=new FcmSender(){public boolean isConfigured(){return true;}public PushResult send(String token,Push push){sent.incrementAndGet();return new PushResult(true,false,"SYNTHETIC-C1-ACCEPTED");}};
            var dispatch=new NotificationDispatchService(Optional.of(app.getBean(Authentication.class)),Optional.of(notificationStore),Optional.of(interests),Optional.of(app.getBean(SeverityReader.class)),Optional.of(app.getBean(DisasterReader.class)),Optional.of(app.getBean(TokenCipher.class)),Optional.of(fakeSender),Optional.empty());
            var change=new DisasterChange(disaster,ChangeKind.NEW,null);
            UUID impact=UUID.randomUUID();sql.update("insert into disaster_area values(?,?,?,'IMPACT','SOURCE_ADDRESS',?,now())",impact,disaster,city,source);
            assertThat(interests.isRelated(disaster,subscribed.get(city))).as("Known team adapter defect: source address is treated as impact").isTrue();
            assertThat(queries.region(disaster)).isEqualTo("SYNTHETIC CITY"); // Existing display contract preserved.
            assertThat(queries.notificationRegion(disaster)).isNull();
            assertThat(app.getBean(DisasterReader.class).findOfficial(disaster).regionName()).isNull();
            assertThat(dispatch.process(change,Instant.now()).createdEvents()).isZero();assertThat(sent.get()).isZero();
            sql.update("update disaster_area set basis='OFFICIAL' where disaster_area_id=?",impact);
            assertThat(queries.notificationRegion(disaster)).isEqualTo("SYNTHETIC CITY");
            assertThat(dispatch.process(change,Instant.now()).successfulDevices()).isEqualTo(1);assertThat(sent.get()).isEqualTo(1);
            assertThat(dispatch.process(change,Instant.now()).createdEvents()).isZero();assertThat(sent.get()).isEqualTo(1);
            assertThat(interests.isRelated(disaster,subscribed.get(city))).isTrue();
            assertThat(interests.isRelated(disaster,subscribed.get(dong))).isTrue();
            assertThat(interests.isRelated(disaster,subscribed.get(root))).as("Ancestor subscription misses descendant impact; broader subscription product contract unconfirmed").isFalse();
            assertThat(interests.isRelated(disaster,subscribed.get(other))).isFalse();
            sql.update("update disaster_area set area_code=? where disaster_area_id=?",dong,impact);
            assertThat(interests.isRelated(disaster,subscribed.get(city))).as("City subscription misses dong impact").isFalse();
            sql.update("update admin_area set active=false where area_code=?",dong);
            assertThat(interests.isRelated(disaster,subscribed.get(dong))).isFalse();
            sql.update("update admin_area set active=true where area_code=?",dong);
            sql.update("update admin_area set parent_area_code=? where area_code=?",dong,root); // cycle terminates via UNION distinct
            assertThat(interests.isRelated(disaster,subscribed.get(other))).isFalse();
            assertThat(interests.isRelated(disaster,UUID.randomUUID())).isFalse();
            sql.update("delete from disaster_area where disaster_area_id=?",impact);
            assertThat(queries.notificationRegion(disaster)).isNull();
            assertThat(interests.isRelated(disaster,subscribed.get(city))).isFalse();
            status.setRollbackOnly();
        });
    }
    record RegionCase(String zone,String city,String dong,String other,UUID member,UUID interest,UUID record,UUID disaster,
            Map<String,Object> payload,java.util.concurrent.atomic.AtomicInteger sends,java.util.concurrent.atomic.AtomicBoolean fail,
            NotificationDispatchService dispatch) {}
    private RegionCase regionCase(String mode){
        // Independent synthetic fixture: do not rely on an earlier ordered test to register the rain source.
        if(sql.queryForObject("select count(*) from data_source where endpoint_key='KMA_HEAVY_RAIN'",Long.class)==0)sql.update("insert into data_source values(?,'SYNTHETIC REGION RAIN','DISASTER','KMA_HEAVY_RAIN',5,true)",UUID.randomUUID());
        String p="RG"+UUID.randomUUID().toString().substring(0,7),city=p+"C",dong=p+"D",other=p+"O",zone=p+"Z";
        sql.update("insert into admin_area(area_code,name,level,active) values(?,'SYNTHETIC PROVINCE','SIDO',true)",p);
        sql.update("insert into admin_area(area_code,name,level,parent_area_code,active) values(?,'의정부시','SIGUNGU',?,true),(?,'양주시','SIGUNGU',?,true),(?,'호원동','EUPMYEONDONG',?,true)",city,p,other,p,dong,city);
        var member=actor();var area=app.getBean(com.disasterinfo.eodiisseo.area.service.AreaService.class).create(member.memberId(),city,"SYNTHETIC subscription");
        UUID interest=UUID.fromString(area.get("interestAreaId").toString());
        Set<String> codes=switch(mode){case "CITY"->Set.of(city);case "MULTI"->Set.of(dong,other);case "UNRELATED"->Set.of(other);default->Set.of(dong);};
        app.getBean(FixtureMappings.class).mappings.put(zone,new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping(zone,codes,URI.create("https://www.weather.go.kr/synthetic-mapping"),"SYNTHETIC-SIGUNGU-V1",Instant.parse("2026-01-01T00:00:00Z"),Instant.now().plusSeconds(3600),true));
        Map<String,Object> payload=new LinkedHashMap<>(Map.of("stnId","108","tmFc","202611011000","tmSeq","1","areaCode",zone,"areaName","UNTRUSTED PROVIDER LABEL","warnVar","2","warnStress","0","command","1","startTime","202611011000","cancel","0"));
        UUID record=ingestRegion(payload);UUID disaster=sql.queryForObject("select disaster_id from source_disaster where source_disaster_id=?",UUID.class,record);
        Store store=app.getBean(Store.class);tx.executeWithoutResult(st->{store.initializeSettings(member.memberId(),Instant.now());store.saveSettings(member.memberId(),new Settings(true,null,true,Instant.now()));});
        app.getBean(NotificationService.class).registerDevice(new MemberSession(member.memberId(),member.sessionId()),p+"-device",new com.disasterinfo.eodiisseo.notification.dto.NotificationDtos.DeviceRequest("SYNTHETIC-REGION-"+p,"ANDROID"));
        var sent=new java.util.concurrent.atomic.AtomicInteger();var fail=new java.util.concurrent.atomic.AtomicBoolean();
        FcmSender sender=new FcmSender(){public boolean isConfigured(){return true;}public PushResult send(String t,Push push){sent.incrementAndGet();return fail.get()?new PushResult(false,false,null,PushOutcome.TRANSIENT_FAILURE):new PushResult(true,false,"SYNTHETIC-ACCEPTED");}};
        var dispatch=new NotificationDispatchService(Optional.of(app.getBean(Authentication.class)),Optional.of(store),Optional.of(app.getBean(InterestAreas.class)),Optional.of(app.getBean(SeverityReader.class)),Optional.of(app.getBean(DisasterReader.class)),Optional.of(app.getBean(TokenCipher.class)),Optional.of(sender),Optional.of((event,delivery,at)->delivery.attemptNo()<2));
        return new RegionCase(zone,city,dong,other,member.memberId(),interest,record,disaster,payload,sent,fail,dispatch);
    }
    private UUID ingestRegion(Map<String,Object> row){
        var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.HEAVY_RAIN;var client=fixtureClient(provider,()->List.of(row));
        // Actual provider decoder and normalization; only transport and freshness publication are synthetic.
        var decoded=client.page("SYNTHETIC KEY",LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),1).items().getFirst();
        UUID source=source("KMA_HEAVY_RAIN");var result=app.getBean(com.disasterinfo.eodiisseo.disaster.collection.OfficialRecordNormalizer.class).normalize(source,provider,decoded,com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.canonical(decoded),Instant.now());
        assertThat(result.sourceRecord()).isNotNull();
        // Test-only publication freshness, not a claim of real provider coverage or successful live collection.
        sql.update("insert into collection_run(run_id,source_id,mode,started_at,finished_at,status,trigger_type) values(?,?,'LIVE',now(),now(),'SUCCESS','MANUAL')",UUID.randomUUID(),source);
        return result.sourceRecord();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"CITY","DONG","MULTI","UNRELATED"}) @Order(110)
    void normalizedOfficialCodesReachTheUnmodifiedAreaAdapterAndFakeSender(String mode){
        var f=regionCase(mode);Set<String> expected=mode.equals("MULTI")?Set.of(f.city(),f.other()):mode.equals("UNRELATED")?Set.of(f.other()):Set.of(f.city());
        assertThat(new HashSet<>(sql.queryForList("select area_code from disaster_area where disaster_id=? and area_role='IMPACT' and basis='OFFICIAL'",String.class,f.disaster()))).isEqualTo(expected);
        var areas=app.getBean(InterestAreas.class);assertThat(areas.isRelated(f.disaster(),f.interest())).isEqualTo(!mode.equals("UNRELATED"));
        assertThat(app.getBean(com.disasterinfo.eodiisseo.area.repository.AreaRepository.class).region(f.city())).isNotNull();
        var report=f.dispatch().process(new DisasterChange(f.disaster(),ChangeKind.NEW,null),Instant.now());
        assertThat(report.successfulDevices()).isEqualTo(mode.equals("UNRELATED")?0:1);assertThat(f.sends().get()).isEqualTo(mode.equals("UNRELATED")?0:1);
        String proof=sql.queryForObject("select new_value::text from disaster_change where disaster_id=? and new_value -> 'officialImpactNormalization' is not null order by changed_at desc limit 1",String.class,f.disaster());
        assertThat(proof).contains("SYNTHETIC-SIGUNGU-V1",f.zone(),"catalogPath");if(!mode.equals("CITY")&&!mode.equals("UNRELATED"))assertThat(proof).contains(f.dong());
        long audit=sql.queryForObject("select count(*) from disaster_change where disaster_id=?",Long.class,f.disaster());
        app.getBean(OfficialImpactAreaSynchronizer.class).refresh(f.record(),Instant.now());assertThat(sql.queryForObject("select count(*) from disaster_change where disaster_id=?",Long.class,f.disaster())).isEqualTo(audit);
    }
    @Test @Order(111) void changedOrExpiredMappingBlocksRetriesBeforeAnyRefreshAndReplacesCurrentCodes(){
        var f=regionCase("DONG");f.fail().set(true);f.dispatch().process(new DisasterChange(f.disaster(),ChangeKind.NEW,null),Instant.now());assertThat(f.sends().get()).isEqualTo(1);
        UUID event=sql.queryForObject("select event_id from notification_event where member_id=? and disaster_id=?",UUID.class,f.member(),f.disaster());
        var mappings=app.getBean(FixtureMappings.class).mappings;var old=mappings.get(f.zone());
        mappings.put(f.zone(),new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping(f.zone(),Set.of(f.other()),old.evidenceUrl(),"SYNTHETIC-V2",old.validFrom(),old.validUntil(),true));
        assertThat(app.getBean(OfficialDisasterQueryService.class).notificationRegion(f.disaster())).isNull();
        f.fail().set(false);assertThat(f.dispatch().retry(event,Instant.now()).successfulDevices()).isZero();assertThat(f.sends().get()).isEqualTo(1);
        app.getBean(OfficialImpactAreaSynchronizer.class).refresh(f.record(),Instant.now());
        assertThat(sql.queryForList("select area_code from disaster_area where disaster_id=?",String.class,f.disaster())).containsExactly(f.other());
        assertThat(app.getBean(InterestAreas.class).isRelated(f.disaster(),f.interest())).isFalse();
        mappings.put(f.zone(),new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping(f.zone(),Set.of(f.other()),old.evidenceUrl(),"SYNTHETIC-EXPIRED",old.validFrom(),Instant.now().minusSeconds(1),true));
        assertThat(app.getBean(OfficialDisasterQueryService.class).notificationRegion(f.disaster())).isNull();
        app.getBean(OfficialImpactAreaSynchronizer.class).refresh(f.record(),Instant.now());
        assertThat(sql.queryForObject("select count(*) from disaster_area where disaster_id=?",Long.class,f.disaster())).isZero();
        assertThat(sql.queryForObject("select count(*) from disaster_change where disaster_id=? and new_value::text like '%SYNTHETIC-V2%'",Long.class,f.disaster())).isEqualTo(1);
    }
    @Test @Order(112) void selectionDisabledAndOfficialLiftCannotSendAndOccurrenceOrAddressCannotCreateImpact(){
        var f=regionCase("DONG");app.getBean(InterestAreas.class).replaceSelection(f.member(),Set.of());
        assertThat(f.dispatch().process(new DisasterChange(f.disaster(),ChangeKind.NEW,null),Instant.now()).successfulDevices()).isZero();assertThat(f.sends().get()).isZero();
        app.getBean(InterestAreas.class).replaceSelection(f.member(),Set.of(f.interest()));
        f.payload().put("tmFc","202611011100");f.payload().put("command","2");f.payload().put("endTime","202611011100");ingestRegion(f.payload());
        assertThat(app.getBean(com.disasterinfo.eodiisseo.disaster.port.DisasterImpactAreaReader.class).find(f.disaster(),Instant.now()).activeIncident()).isFalse();
        assertThat(f.dispatch().process(new DisasterChange(f.disaster(),ChangeKind.NEW,null),Instant.now()).successfulDevices()).isZero();
        UUID quake=write("region-epicenter-"+UUID.randomUUID(),"first");assertThat(sql.queryForObject("select count(*) from disaster_area where disaster_id=?",Long.class,quake)).isZero();
        assertThat(app.getBean(OfficialDisasterQueryService.class).notificationRegion(quake)).isNull();
    }
    @Test @Order(113) void impactWriteFailureRollsBackRevisionOutboxAndRegionProofTogether(){
        var f=regionCase("DONG");long revisions=sql.queryForObject("select count(*) from source_disaster_revision where source_disaster_id=?",Long.class,f.record());long events=sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=?",Long.class,f.disaster());
        var mappings=app.getBean(FixtureMappings.class).mappings;var old=mappings.get(f.zone());mappings.put(f.zone(),new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping(f.zone(),Set.of(f.other()),old.evidenceUrl(),"SYNTHETIC-ROLLBACK",old.validFrom(),old.validUntil(),true));
        sql.execute("create function region_test_reject() returns trigger language plpgsql as $$ begin raise exception 'SYNTHETIC impact write failure' using errcode='23514'; end $$");
        sql.execute("create trigger region_test_reject before insert on disaster_area for each row execute function region_test_reject()");
        try{
            f.payload().put("tmFc","202611011130");f.payload().put("command","7");
            assertThatThrownBy(()->ingestRegion(f.payload())).isInstanceOf(RuntimeException.class);
        }finally{sql.execute("drop trigger region_test_reject on disaster_area");sql.execute("drop function region_test_reject()");}
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision where source_disaster_id=?",Long.class,f.record())).isEqualTo(revisions);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=?",Long.class,f.disaster())).isEqualTo(events);
        assertThat(sql.queryForList("select area_code from disaster_area where disaster_id=?",String.class,f.disaster())).containsExactly(f.city());
        assertThat(sql.queryForObject("select count(*) from disaster_change where disaster_id=? and new_value::text like '%SYNTHETIC-ROLLBACK%'",Long.class,f.disaster())).isZero();
    }
    @Test @Order(114) void identicalArchivedWarningRefreshesTheMappingWithoutReplayingRevisionOrOutbox(){
        var f=regionCase("DONG");var provider=com.disasterinfo.eodiisseo.disaster.collection.OfficialProviderClient.Provider.HEAVY_RAIN;
        var client=fixtureClient(provider,()->List.of(f.payload()));UUID source=source("KMA_HEAVY_RAIN");
        assertThat(collect(source,client)).isEqualTo("SUCCESS");
        long revisions=sql.queryForObject("select count(*) from source_disaster_revision where source_disaster_id=?",Long.class,f.record());
        long events=sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=?",Long.class,f.disaster());
        var maps=app.getBean(FixtureMappings.class).mappings;var old=maps.get(f.zone());
        maps.put(f.zone(),new com.disasterinfo.eodiisseo.disaster.port.OfficialWarningAreaMappingPort.Mapping(f.zone(),Set.of(f.other()),old.evidenceUrl(),"SYNTHETIC-DUPLICATE-REFRESH",old.validFrom(),old.validUntil(),true));
        assertThat(collect(source,client)).isEqualTo("SUCCESS");
        assertThat(sql.queryForList("select area_code from disaster_area where disaster_id=?",String.class,f.disaster())).containsExactly(f.other());
        assertThat(sql.queryForObject("select count(*) from source_disaster_revision where source_disaster_id=?",Long.class,f.record())).isEqualTo(revisions);
        assertThat(sql.queryForObject("select count(*) from disaster_runtime_outbox where disaster_id=?",Long.class,f.disaster())).isEqualTo(events);
    }
    /** Known team-owned limitation, kept as an audit reproduction rather than accepted notification policy. */
    @Test @Order(115) void subscribedParentSigunguStillMissesAChildGuUnderTheTeamAncestorOnlyRule(){
        var f=regionCase("CITY");
        String parent=sql.queryForObject("select parent_area_code from admin_area where area_code=?",String.class,f.city());
        tx.executeWithoutResult(st->{
            sql.update("update admin_area set name='수원시',level='SIGUNGU' where area_code=?",parent);
            sql.update("update admin_area set name='영통구' where area_code=?",f.city());
            var actor=actor();var selected=app.getBean(com.disasterinfo.eodiisseo.area.service.AreaService.class).create(actor.memberId(),parent,"SYNTHETIC parent city");
            UUID parentInterest=UUID.fromString(selected.get("interestAreaId").toString());
            assertThat(app.getBean(com.disasterinfo.eodiisseo.area.repository.AreaRepository.class).region(parent)).isNotNull();
            assertThat(app.getBean(InterestAreas.class).isRelated(f.disaster(),f.interest())).isTrue();
            assertThat(app.getBean(InterestAreas.class).isRelated(f.disaster(),parentInterest)).as("Known team defect: a subscribed parent SIGUNGU is not matched to an officially impacted child gu").isFalse();
            st.setRollbackOnly();
        });
    }
}

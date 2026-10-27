package com.disasterinfo.eodiisseo.auth.repository;

import com.disasterinfo.eodiisseo.EodiisseoApplication;
import com.disasterinfo.eodiisseo.auth.security.TokenService;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.service.AuthService;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.chat.ChatException;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryAuthPort;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardSessionVerifier;
import com.disasterinfo.eodiisseo.dashboard.port.DashboardWeatherReader;
import com.disasterinfo.eodiisseo.notification.NotificationContracts;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static com.disasterinfo.eodiisseo.auth.repository.CanonicalDatabaseIntegrationTest.*;

/** Production Spring wiring, JDBC + JPA, real security filter and all exception handlers together. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
class LocalApplicationIntegrationTest {
    @BeforeAll static void prepare(){CanonicalDatabaseIntegrationTest.createDatabase();}
    @AfterAll static void cleanup(){CanonicalDatabaseIntegrationTest.dropOnlyCreatedDatabase();}
    @Test @Timeout(60) void realHttpRolesLogoutAndWithdrawalUseOneDatabase() throws Exception {
        try(var context=new SpringApplicationBuilder(EodiisseoApplication.class).web(WebApplicationType.SERVLET).run(
            "--server.address=127.0.0.1","--server.port=0","--spring.profiles.active=local-db-verification",
            "--spring.datasource.url="+source.getUrl(),"--spring.datasource.username=eodiisseo_audit","--spring.datasource.password=",
            "--spring.flyway.enabled=true","--spring.jpa.hibernate.ddl-auto=validate","--AUTH_BRIDGES_ENABLED=true",
            "--JWT_SECRET=test-only-key-0123456789abcdefghijk","--debug=false","--logging.level.root=WARN")){
            assertEquals(1,context.getBeansOfType(ChatAuthenticationPort.class).size());
            assertEquals(1,context.getBeansOfType(SummaryAuthPort.class).size());
            assertEquals(1,context.getBeansOfType(DashboardSessionVerifier.class).size());
            assertEquals(1,context.getBeansOfType(DashboardWeatherReader.class).size());
            assertEquals(1,context.getBeansOfType(NotificationContracts.Authentication.class).size());
            UUID member=member(),session=UUID.randomUUID();Instant now=Instant.now();
            db.update("insert into auth_session values(?,?,?,?,?,null)",session,member,AuthService.hash("test-only-"+session),now,now.plus(Duration.ofDays(14)));
            String token=context.getBean(TokenService.class).issue(member,session,now.plus(Duration.ofDays(14)));
            int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();String base="http://127.0.0.1:"+port;
            try(var client=HttpClient.newHttpClient()){
                assertEquals(401,send(client,base+"/api/v1/members/me",null,"GET",null));
                assertEquals(200,send(client,base+"/api/v1/members/me",token,"GET",null));
                assertEquals(200,send(client,base+"/api/v1/checklists?disasterType=EARTHQUAKE",token,"GET",null));
                assertEquals(403,send(client,base+"/api/v1/ops/status",token,"GET",null));
                db.update("insert into member_role values(?,'OPS',?)",member,now);
                assertEquals(200,send(client,base+"/api/v1/ops/status",token,"GET",null));
                assertEquals(401,send(client,base+"/api/v1/auth/refresh",null,"POST","{\"refreshToken\":\"test-only-invalid\"}"));
                assertEquals(400,send(client,base+"/api/v1/auth/refresh",null,"POST","{}"));
                assertEquals(400,send(client,base+"/api/v1/groups/not-a-uuid",token,"GET",null));
                assertEquals(204,send(client,base+"/api/v1/auth/logout",token,"POST",null));
                assertEquals(401,send(client,base+"/api/v1/members/me",token,"GET",null));
            }
            db.update("update auth_session set revoked_at=null where session_id=?",session);
            var chat=context.getBean(ChatAuthenticationPort.class);
            var applicationDatabase=context.getBean(OwnedDatabase.class);
            chat.runWithActiveSession(member,session,()->applicationDatabase.update("insert into ai_answer(answer_id,member_id,question_text,answer_text,status,created_at,completed_at,expires_at) values(?,?,'test-only','test-only','COMPLETED',?,?,?)",UUID.randomUUID(),member,now,now,now.plus(Duration.ofDays(90))));
            context.getBean(AuthService.class).withdraw(new MemberPrincipal(member,session));
            assertEquals(0,db.count("select count(*) from ai_answer where member_id=?",member));
            assertEquals(0,db.count("select count(*) from member_role where member_id=? and role='OPS'",member));
            AtomicBoolean saved=new AtomicBoolean();assertThrows(ChatException.class,()->chat.runWithActiveSession(member,session,()->saved.set(true)));assertFalse(saved.get());
        }
    }
    private static int send(HttpClient client,String url,String token,String method,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}

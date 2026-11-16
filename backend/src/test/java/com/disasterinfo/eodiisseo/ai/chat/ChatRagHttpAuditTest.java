package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.EodiisseoApplication;
import com.disasterinfo.eodiisseo.ai.chat.adapter.*;
import com.disasterinfo.eodiisseo.ai.chat.port.*;
import com.disasterinfo.eodiisseo.ai.ollama.*;
import com.disasterinfo.eodiisseo.ai.resource.*;
import com.disasterinfo.eodiisseo.ai.resource.adapter.*;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.resource.port.*;
import com.disasterinfo.eodiisseo.ai.resource.vector.*;
import com.disasterinfo.eodiisseo.auth.repository.*;
import com.disasterinfo.eodiisseo.auth.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring HTTP/JWT/ACTIVE sessions/consent/approval/JPA; fake model transport and fake array vectors only. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Timeout(120)
class ChatRagHttpAuditTest {
    static final JsonMapper JSON=JsonMapper.builder().build();static final String INDOOR="지진이 발생했을 때 실내에서는 어떻게 해야 하나?";
    JdbcTemplate admin,sql;String database;ConfigurableApplicationContext context;Scenario state;HttpClient http;String base,token;UUID member,session;MemberPrincipal reviewer;
    static final class AuditClock extends Clock {volatile Instant now=Instant.now();public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}}
    static final class Scenario implements ChatEvidenceGenerationPolicy {
        final AuditClock clock=new AuditClock();final AtomicInteger generated=new AtomicInteger();String mode="LEXICAL",fault="OK";volatile boolean failSearch,failApproval;volatile Runnable duringModel=()->{},beforeCommit=()->{},atSave=()->{};
        JdbcTemplate failureDatabase;ChatOfficialResourceSearchPort lexical,vector;String outbound;final Map<UUID,String> plans=new ConcurrentHashMap<>();
        ChatOfficialResourceSearchPort selected(){return mode.equals("VECTOR")?vector:lexical;}
        public SearchResult search(String question){if(failSearch)failureDatabase.queryForObject("select count(*) from deliberately_missing_chat_search_table",Long.class);return selected().search(question);}
        public ChatAnswerGenerationPort guardGeneration(ChatAnswerGenerationPort delegate){return ((ChatEvidenceGenerationPolicy)selected()).guardGeneration(delegate);}
        OllamaChatClient.Response respond(String user){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();generated.incrementAndGet();outbound=user;
            var data=JSON.readTree(user);var citations=new ArrayList<Map<String,Object>>();var ids=new ArrayList<String>();var text=new ArrayList<String>();
            for(var r:data.path("resources")){String id=r.path("resourceId").asString();ids.add(id);for(var p:r.path("paragraphs")){String quote=p.asString();text.add(quote);citations.add(Map.of("resourceId",id,"excerpt",quote));}}
            switch(fault){
                case "FORGED_UUID" -> {String id=UUID.randomUUID().toString();ids= new ArrayList<>(List.of(id));citations=new ArrayList<>(List.of(Map.of("resourceId",id,"excerpt",text.getFirst())));}
                case "UNPROVIDED","OTHER_HAZARD","ADDITION","NEGATION","MODIFIED" -> {
                    String quote=switch(fault){case "OTHER_HAZARD"->SyntheticRagEvaluation.documents().get(6).paragraphs().getFirst();case "MODIFIED"->text.getFirst()+" SYNTHETIC 변조";case "NEGATION"->text.getFirst().replace("이용하지 마세요","이용하세요").replace("진입하지 마세요","진입하세요");case "ADDITION"->text.getFirst()+" SYNTHETIC 추가 행동입니다.";default->"SYNTHETIC 제공하지 않은 문단입니다.";};
                    text=new ArrayList<>(List.of(quote));citations=new ArrayList<>(List.of(Map.of("resourceId",ids.getFirst(),"excerpt",quote)));
                }
                case "MISSING_CONTEXT" -> {text=new ArrayList<>(text.subList(0,1));citations=new ArrayList<>(citations.subList(0,1));ids=new ArrayList<>(List.of(ids.getFirst()));}
                case "TIMEOUT" -> clock.now=clock.now.plus(ChatService.RESPONSE_LIMIT).plusSeconds(1);
            }
            duringModel.run();return new OllamaChatClient.Response(JSON.writeValueAsString(Map.of("answer",String.join("\n",text),"usedResourceIds",ids,"sufficientEvidence",true,"citations",citations)),"SYNTHETIC-NO-MODEL",Duration.ofMillis(1));
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Fixture {
        @Bean Scenario scenario(){return new Scenario();}
        @Bean @Primary Clock auditClock(Scenario s){return s.clock;}
        @Bean @Primary ChatOfficialResourceSearchPort auditSearch(Scenario s){return s;}
        @Bean @Primary ResourceApprovalPort auditApprovals(Scenario s,DatabaseResourceApproval actual){return id->{if(s.failApproval)throw new IllegalStateException("SYNTHETIC provider failure");return actual.findApproval(id);};}
        @Bean @Primary ChatAnswerGenerationPort auditGenerator(Scenario s){var client=mock(OllamaChatClient.class);when(client.isConfigured()).thenReturn(true);when(client.modelName()).thenReturn("SYNTHETIC-NO-MODEL");when(client.chat(anyString(),anyString(),anyMap(),any())).thenAnswer(call->s.respond(call.getArgument(1)));return new OllamaChatAnswerGenerator(client,s.clock);}
        @Bean @Primary ChatAnswerStore auditAnswers(Scenario s,JpaChatAnswerStore actual){return new ChatAnswerStore(){public boolean isAvailable(){return actual.isAvailable();}public void save(com.disasterinfo.eodiisseo.ai.chat.entity.ChatAnswer answer,List<UUID> ids){actual.save(answer,ids);s.atSave.run();}public int deleteExpired(Instant now){return actual.deleteExpired(now);}public int deleteByMemberId(UUID id){return actual.deleteByMemberId(id);}};}
        @Bean @Primary ChatEvidenceCommitPort auditCommit(Scenario s,DatabaseChatEvidenceCommit actual){return (e,p)->{s.beforeCommit.run();actual.withCurrentEvidence(e,p);};}
        @Bean @Primary ChatService auditChat(ChatAuthenticationPort auth,ChatOfficialResourceSearchPort search,ChatAnswerGenerationPort generation,ChatAnswerStore store,ChatRetentionPolicy retention,ChatPromptPrivacyPort privacy,Clock clock,ChatEvidenceCommitPort commit){return new ChatService(Optional.of(auth),Optional.of(search),Optional.of(generation),Optional.of(store),Optional.of(retention),Optional.of(privacy),clock,Optional.of(commit));}
    }
    @BeforeAll void open(){
        String url=System.getenv("EODIISSEO_AUDIT_JDBC_URL");if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE"))||!"jdbc:postgresql://127.0.0.1:55433/postgres".equals(url))throw new IllegalStateException("Disposable 55433 only");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"eodiisseo_audit",""));database="owned_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+database);
        context=new SpringApplicationBuilder(EodiisseoApplication.class,Fixture.class).web(WebApplicationType.SERVLET).run(
            "--server.address=127.0.0.1","--server.port=0","--spring.profiles.active=chat-rag-http-audit",
            "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55433/"+database,"--spring.datasource.username=eodiisseo_audit","--spring.datasource.password=",
            "--spring.config.import=optional:classpath:/nonexistent-chat-rag-audit.properties","--spring.flyway.enabled=true","--spring.flyway.locations=classpath:db/migration,classpath:db/owned-runtime",
            "--spring.jpa.hibernate.ddl-auto=validate","--AUTH_BRIDGES_ENABLED=true","--eodiisseo.owned-http.enabled=true","--eodiisseo.ai.external-processing.enabled=true",
            "--eodiisseo.ai.ollama.enabled=false","--eodiisseo.fcm.enabled=false","--eodiisseo.disaster.collection.enabled=false","--eodiisseo.collection.enabled=false","--COLLECTION_WORKER_ENABLED=false","--eodiisseo.owned-runtime.enabled=false","--eodiisseo.ai.retention-worker.enabled=false","--eodiisseo.runtime.enabled=false","--eodiisseo.retention.enabled=false","--eodiisseo.notification.retry-worker.enabled=false","--eodiisseo.notification.crypto.enabled=false",
            "--JWT_SECRET=SYNTHETIC-CHAT-HTTP-AUDIT-0123456789abcdef","--logging.level.root=WARN");
        sql=context.getBean(JdbcTemplate.class);state=context.getBean(Scenario.class);state.failureDatabase=sql;new ResourceDatabasePopulator(new ClassPathResource("ai/rag_vector_metadata.sql")).execute(context.getBean(javax.sql.DataSource.class));
        base="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        assertThat(context.getBean(ChatEvidenceCommitPort.class)).isNotNull();assertThat(context.getBean(ChatAuthenticationPort.class)).isNotNull();
    }
    @AfterAll void close(){if(http!=null)http.close();if(context!=null)context.close();if(admin!=null&&database!=null&&database.matches("owned_[a-f0-9]{32}"))admin.execute("drop database "+database+" with(force)");}
    @BeforeEach void reset(){
        state.clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);state.mode="LEXICAL";state.fault="OK";state.generated.set(0);state.failSearch=false;state.failApproval=false;state.duringModel=()->{};state.beforeCommit=()->{};state.atSave=()->{};state.plans.clear();state.outbound=null;
        for(String table:List.of("ai_answer_resource","ai_answer","rag_test_vector","rag_vector_chunk","rag_vector_group","rag_vector_work","ai_resource_review","owned_resource_import"))sql.update("delete from "+table);
        for(var d:SyntheticRagEvaluation.documents())sql.update("delete from official_resource where resource_id=?",d.id());
        var actor=actor(false);member=actor.memberId();session=actor.sessionId();reviewer=actor(true);
        context.getBean(ExternalAiConsentService.class).record(member,session,true);token=context.getBean(TokenService.class).issue(member,session,state.clock.now.plusSeconds(3600));
        var resources=context.getBean(JdbcOfficialResourceStore.class);var approval=context.getBean(ResourceApprovalPort.class);var actual=context.getBean(DatabaseResourceApproval.class);
        var fake=new ChatAuditVectors(sql);var vectors=new JdbcRagVectorStore(sql,context.getBean(PlatformTransactionManager.class),fake,state.clock);
        var pipeline=new ApprovedRagPipeline(resources,approval,(id,fp,h,now)->h.equals(state.plans.get(id)),vectors,fake,ChatAuditVectors.MODEL,state.clock);
        for(var d:SyntheticRagEvaluation.documents()){
            var doc=new OfficialResourceDocument(d.id(),"행정안전부",d.title(),"https://safekorea.go.kr/synthetic-chat/"+d.id(),"SYNTHETIC",String.join("\n\n",d.paragraphs()),state.clock.now.minusSeconds(1));resources.append(doc);
            actual.review(reviewer,doc.getResourceId(),doc.revisionFingerprint(),state.clock.now.plusSeconds(120),"SYNTHETIC HTTP TEST ONLY",true,true);
            var paragraphs=new ArrayList<RagChunkGroup.Paragraph>();for(int n=0;n<d.paragraphs().size();n++){String p=d.paragraphs().get(n);paragraphs.add(new RagChunkGroup.Paragraph(UUID.randomUUID(),n,p,EmbeddingPort.sha(p),"SYNTHETIC HTML paragraph "+n,false));}
            var group=new RagChunkGroup(UUID.randomUUID(),d.id(),doc.revisionFingerprint(),d.hazard(),List.of("SYNTHETIC"),"SYNTHETIC-1",paragraphs);state.plans.put(group.id(),group.manifestHash());assertThat(pipeline.index(group,Duration.ofSeconds(10))).isEqualTo("READY");
        }
        state.lexical=pipeline.configure(ApprovedRagPipeline.Mode.LEXICAL,context.getBean(ChatAnswerGenerationPort.class)).search();state.vector=pipeline.configure(ApprovedRagPipeline.Mode.EXACT,context.getBean(ChatAnswerGenerationPort.class)).search();
        // Deliberately inject the raw generator bean, not Bundle.generation: ChatService must enforce the search policy.
    }
    MemberPrincipal actor(boolean ops){UUID m=UUID.randomUUID(),s=UUID.randomUUID();var r=context.getBean(AuthRepository.class);r.createMember(m,"SYNTHETIC",state.clock.now);r.grantUserRole(m,state.clock.now);if(ops)sql.update("insert into member_role values(?,'OPS',?)",m,java.sql.Timestamp.from(state.clock.now));r.createSession(s,m,UUID.randomUUID().toString().replace("-","").repeat(2),state.clock.now,state.clock.now.plus(Duration.ofDays(14)));return new MemberPrincipal(m,s);}
    HttpResponse<String> send(String question,String bearer)throws Exception {var b=HttpRequest.newBuilder(URI.create(base+"/api/v1/ai/guidance")).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");if(bearer!=null)b.header("Authorization","Bearer "+bearer);return http.send(b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("question",question)))).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode ok(String q)throws Exception {var response=send(q,token);assertThat(response.statusCode()).isEqualTo(200);return JSON.readTree(response.body());}
    long count(String table){return sql.queryForObject("select count(*) from "+table,Long.class);}
    void stored(JsonNode body,String status){assertThat(count("ai_answer")).isEqualTo(1);var row=sql.queryForMap("select * from ai_answer");assertThat(row.get("status")).isEqualTo(status);assertThat(row.get("answer_text")).isEqualTo(body.path("answer").asString());assertThat(((java.sql.Timestamp)row.get("completed_at")).toInstant()).isEqualTo(Instant.parse(body.path("generatedAt").asString()));assertThat(((java.sql.Timestamp)row.get("expires_at")).toInstant()).isEqualTo(((java.sql.Timestamp)row.get("created_at")).toInstant().plus(Duration.ofDays(90)));}
    void fallback(String q,String status)throws Exception{var body=ok(q);assertThat(body.path("answerType").asString()).isEqualTo("FALLBACK");assertThat(body.path("sources").isEmpty()).isTrue();assertThat(count("ai_answer_resource")).isZero();stored(body,status);}
    @ParameterizedTest @ValueSource(strings={"LEXICAL","VECTOR"})
    void sixRequiredQuestionsUseAuthenticatedMinimizedApprovedEvidenceAndPersistHttpResults(String mode)throws Exception{
        state.mode=mode;String[] questions={INDOOR,"지진이 나면 엘리베이터를 이용해도 되나?","호우가 내릴 때 지하차도에 접근해도 되나?","침수된 지하공간에서는 어떻게 행동해야 하나?","산불이 발생했을 때 어떻게 대피해야 하나?","산불 연기가 보일 때 주의할 점은 무엇인가?"};int[] docs={0,1,3,4,6,8};
        for(int n=0;n<questions.length;n++){
            sql.update("delete from ai_answer_resource");sql.update("delete from ai_answer");var body=ok("SYNTHETIC 홍길동 010-1234-5678 서울시 가상주소; "+questions[n]);assertThat(body.propertyNames()).containsExactlyInAnyOrder("answerType","answer","sources","generatedAt");assertThat(body.path("answerType").asString()).isEqualTo("AI");stored(body,"COMPLETED");
            var used=sql.queryForList("select resource_id from ai_answer_resource",UUID.class);var gold=SyntheticRagEvaluation.documents().get(docs[n]);if(mode.equals("VECTOR"))assertThat(used).containsExactly(gold.id());else {assertThat(used).contains(gold.id());assertThat(used).allMatch(id->SyntheticRagEvaluation.documents().stream().anyMatch(d->d.id().equals(id)&&d.hazard().equals(gold.hazard())));}assertThat(state.outbound).doesNotContain("홍길동","010-1234-5678","가상주소");
        }
    }
    @ParameterizedTest @ValueSource(strings={"LEXICAL","VECTOR"})
    void unsupportedQueriesCannotGenerateAnswersOrFakeCitations(String mode)throws Exception{state.mode=mode;for(String q:List.of("지진과 산불 대피 방법","근거 없는 음식 질문","현재 내 위치의 호우 위험 여부","호우 대피소로 안전한 경로 알려줘")){sql.update("delete from ai_answer_resource");sql.update("delete from ai_answer");fallback(q,"FALLBACK");}assertThat(state.generated.get()).isZero();}
    @ParameterizedTest @ValueSource(strings={"FORGED_UUID","UNPROVIDED","NEGATION","MISSING_CONTEXT","OTHER_HAZARD","ADDITION","MODIFIED"})
    void invalidModelOutputCannotBecomeAnAiAnswerInEitherStrategy(String fault)throws Exception{for(String mode:List.of("LEXICAL","VECTOR")){state.mode=mode;state.fault=fault;sql.update("delete from ai_answer_resource");sql.update("delete from ai_answer");fallback(fault.equals("NEGATION")?"호우 지하차도 행동요령":INDOOR,"FAILED");}}
    @ParameterizedTest @ValueSource(strings={"LEXICAL_REVOKE","LEXICAL_CHANGE","LEXICAL_EXPIRE","LEXICAL_APPROVAL_FAILURE","VECTOR_REVOKE","VECTOR_CHANGE","VECTOR_EXPIRE","VECTOR_APPROVAL_FAILURE"})
    void mutationDuringGenerationCannotPublishOrLinkStaleEvidence(String fault)throws Exception{state.mode=fault.substring(0,fault.indexOf('_') );String event=fault.substring(fault.indexOf('_')+1);state.duringModel=()->{switch(event){case "REVOKE"->context.getBean(DatabaseResourceApproval.class).revoke(reviewer,SyntheticRagEvaluation.documents().getFirst().id());case "CHANGE"->sql.update("update official_resource set content_text='SYNTHETIC CHANGED' where resource_id=?",SyntheticRagEvaluation.documents().getFirst().id());case "EXPIRE"->state.clock.now=state.clock.now.plusSeconds(121);case "APPROVAL_FAILURE"->state.failApproval=true;}};fallback(INDOOR,"INSUFFICIENT");}
    @ParameterizedTest @ValueSource(strings={"LEXICAL_REVOKE","VECTOR_REVOKE","LEXICAL_EXPIRE","VECTOR_EXPIRE"})
    void commitTimeRevocationIsCaughtAfterTheLastSearch(String mode)throws Exception{state.mode=mode.split("_")[0];state.beforeCommit=()->{if(mode.endsWith("EXPIRE"))state.clock.now=state.clock.now.plusSeconds(121);else context.getBean(DatabaseResourceApproval.class).revoke(reviewer,SyntheticRagEvaluation.documents().getFirst().id());};fallback(INDOOR,"INSUFFICIENT");}
    @Test void withdrawnConsentPreventsAnyPersistenceAfterModelResponse()throws Exception{state.mode="VECTOR";state.duringModel=()->context.getBean(ExternalAiConsentService.class).record(member,session,false);assertThat(send(INDOOR,token).statusCode()).isEqualTo(500);assertThat(count("ai_answer")).isZero();assertThat(count("ai_answer_resource")).isZero();}
    @ParameterizedTest @ValueSource(strings={"REVOKED","EXPIRED"})
    void activeSessionIsRecheckedAfterGeneration(String stateValue)throws Exception{state.mode="VECTOR";state.duringModel=()->sql.update(stateValue.equals("REVOKED")?"update auth_session set revoked_at=now() where session_id=?":"update auth_session set created_at=now()-interval '15 days',expires_at=now()-interval '1 day' where session_id=?",session);assertThat(send(INDOOR,token).statusCode()).isEqualTo(401);assertThat(count("ai_answer")).isZero();}
    @Test void unauthenticatedInvalidAndDeniedInputsNeverReachModel()throws Exception{assertThat(send(INDOOR,null).statusCode()).isEqualTo(401);assertThat(send("   ",token).statusCode()).isEqualTo(400);assertThat(count("ai_answer")).isZero();context.getBean(ExternalAiConsentService.class).record(member,session,false);assertThat(send(INDOOR,token).statusCode()).isEqualTo(500);assertThat(count("ai_answer")).isZero();assertThat(state.generated.get()).isZero();}
    @Test void searchFailureAndModelDeadlineUseSafeExistingStatuses()throws Exception{state.mode="VECTOR";state.failSearch=true;fallback(INDOOR,"FAILED");assertThat(state.generated.get()).isZero();sql.update("delete from ai_answer");state.failSearch=false;state.fault="TIMEOUT";fallback(INDOOR,"TIMEOUT");}
    @Test void failedPersistenceRollsBackAndRetriedRequestsAreCurrentlyDistinct()throws Exception{state.atSave=()->{throw new ChatException(ChatException.Reason.PERSISTENCE_FAILURE,"SYNTHETIC");};assertThat(send(INDOOR,token).statusCode()).isEqualTo(500);assertThat(count("ai_answer")).isZero();state.atSave=()->{};assertThat(ok(INDOOR).path("answerType").asString()).isEqualTo("AI");assertThat(ok(INDOOR).path("answerType").asString()).isEqualTo("AI");assertThat(count("ai_answer")).isEqualTo(2);state.clock.now=state.clock.now.plus(Duration.ofDays(91));assertThat(context.getBean(ChatService.class).deleteExpiredAnswers()).isEqualTo(2);assertThat(count("ai_answer_resource")).isZero();}
    @Test void expiryDuringTheActualFlushRollsBackAnswerAndSourceLinks()throws Exception{
        state.mode="VECTOR";state.atSave=()->state.clock.now=state.clock.now.plusSeconds(121);
        assertThat(send(INDOOR,token).statusCode()).isEqualTo(500);assertThat(count("ai_answer")).isZero();assertThat(count("ai_answer_resource")).isZero();
    }
    @Test void approvalRevocationWaitsForTheShortCommitAndNextRequestFallsBack()throws Exception{
        state.mode="VECTOR";var saving=new CountDownLatch(1);var release=new CountDownLatch(1);var revoking=new CountDownLatch(1);
        state.atSave=()->{saving.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("SYNTHETIC gate timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}};
        try(var workers=Executors.newFixedThreadPool(2)){
            var request=workers.submit(()->send(INDOOR,token));try{
                assertThat(saving.await(10,TimeUnit.SECONDS)).isTrue();var revoked=workers.submit(()->{revoking.countDown();context.getBean(DatabaseResourceApproval.class).revoke(reviewer,SyntheticRagEvaluation.documents().getFirst().id());});
                assertThat(revoking.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->revoked.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();assertThat(JSON.readTree(request.get(10,TimeUnit.SECONDS).body()).path("answerType").asString()).isEqualTo("AI");revoked.get(10,TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
        state.atSave=()->{};assertThat(count("ai_answer")).isEqualTo(1);var next=ok(INDOOR);assertThat(next.path("answerType").asString()).isEqualTo("FALLBACK");assertThat(next.path("sources").isEmpty()).isTrue();assertThat(sql.queryForObject("select count(*) from ai_answer where status='COMPLETED'",Long.class)).isEqualTo(1);
    }
    @Test @Timeout(180) void sharedCorpusEvaluationRunsThroughHttpAndStoresNoInvalidEvidence()throws Exception{
        var result=new LinkedHashMap<String,Object>();for(String mode:List.of("LEXICAL","VECTOR")){state.mode=mode;var invalidStored=new AtomicInteger();var metrics=SyntheticRagEvaluation.evaluate(q->{try{var response=ok(q);var selected=sql.queryForList("select r.* from official_resource r join ai_answer_resource ar using(resource_id) join ai_answer a using(answer_id) where a.member_id=? order by a.created_at desc",member);var refs=new ArrayList<ChatOfficialResourceSearchPort.OfficialResource>();if(response.path("answerType").asString().equals("AI")){var seen=new HashSet<UUID>();for(var row:selected){UUID id=(UUID)row.get("resource_id");if(seen.add(id))refs.add(new ChatOfficialResourceSearchPort.OfficialResource(id,(String)row.get("agency_name"),(String)row.get("title"),URI.create((String)row.get("source_url")),String.join("\n\n",Arrays.stream(RagChunkGroup.BOUNDARY.split((String)row.get("content_text"))).filter(p->response.path("answer").asString().contains(p)).toList()),true,true,true));}}if(response.path("answerType").asString().equals("AI")){var gold=SyntheticRagEvaluation.questions().stream().filter(item->item.text().equals(q)).findFirst().orElseThrow();if(gold.unsupported()||refs.stream().noneMatch(ref->ref.resourceId().equals(gold.expected())&&Arrays.asList(RagChunkGroup.BOUNDARY.split(ref.content())).containsAll(gold.requiredParagraphs())))invalidStored.incrementAndGet();}sql.update("delete from ai_answer_resource");sql.update("delete from ai_answer");return new ChatOfficialResourceSearchPort.SearchResult(refs,!refs.isEmpty());}catch(Exception error){throw new IllegalStateException(error);}},SyntheticRagEvaluation.questions(),SyntheticRagEvaluation.documents());assertThat(metrics.failures()).isZero();assertThat(metrics.wrongHazardRate()).isZero();assertThat(metrics.missingContextRate()).isZero();assertThat(metrics.unsupportedResponseRate()).isZero();assertThat(invalidStored.get()).isZero();result.put(mode.equals("VECTOR")?"FAKE_VECTOR_NOT_MODEL_QUALITY":mode,Map.of("metrics",metrics,"wrongGroundedAnswersStored",invalidStored.get()));}
        Path path=Path.of(System.getProperty("java.io.tmpdir"),"eodiisseo-32a-evaluation-"+UUID.randomUUID()+".json");Files.writeString(path,JSON.writeValueAsString(result));System.out.println("SYNTHETIC_EVALUATION_REPORT="+path);
    }
    @Test void absentOutboundConsentStillPersistsTheOriginalLocalQuestionUnderCurrentPolicy()throws Exception{
        sql.update("delete from ai_external_consent where member_id=?",member);
        String raw="SYNTHETIC 홍길동 010-1234-5678 가상주소; "+INDOOR;
        fallback(raw,"FALLBACK");assertThat(state.generated.get()).isZero();
        assertThat(sql.queryForObject("select question_text from ai_answer where member_id=?",String.class,member)).isEqualTo(raw);
    }
    @ParameterizedTest @ValueSource(strings={"DENIED","WITHDRAWN","GRANTING_SESSION_EXPIRED"})
    void explicitRefusalBlocksStorageWhileExpiredOutboundGrantKeepsLocalFallback(String condition)throws Exception{
        var consent=context.getBean(ExternalAiConsentService.class);
        if(condition.equals("DENIED")){sql.update("delete from ai_external_consent where member_id=?",member);consent.record(member,session,false);}
        else if(condition.equals("WITHDRAWN"))consent.record(member,session,false);
        else {
            // Current bearer remains valid; only the session that granted outbound permission expires.
            UUID granting=UUID.randomUUID();var r=context.getBean(AuthRepository.class);
            r.createSession(granting,member,UUID.randomUUID().toString().replace("-","").repeat(2),state.clock.now,state.clock.now.plus(Duration.ofDays(14)));
            consent.record(member,granting,true);
            sql.update("update auth_session set created_at=now()-interval '15 days',expires_at=now()-interval '1 day' where session_id=?",granting);
        }
        int expected=condition.equals("GRANTING_SESSION_EXPIRED")?200:500;
        assertThat(send(INDOOR,token).statusCode()).isEqualTo(expected);
        assertThat(state.generated.get()).isZero();assertThat(count("ai_answer_resource")).isZero();
        assertThat(count("ai_answer")).isEqualTo(expected==200?1:0);
        if(expected==200)assertThat(sql.queryForObject("select status from ai_answer",String.class)).isEqualTo("FALLBACK");
    }
    @Test void memberAnswersAreIsolatedAndActualWithdrawalDeletesOnlyTheOwner()throws Exception{
        assertThat(ok(INDOOR).path("answerType").asString()).isEqualTo("AI");
        UUID first=member;UUID firstSession=session;String firstToken=token;
        var other=actor(false);member=other.memberId();session=other.sessionId();
        context.getBean(ExternalAiConsentService.class).record(member,session,true);
        token=context.getBean(TokenService.class).issue(member,session,state.clock.now.plusSeconds(3600));
        assertThat(ok(INDOOR).path("answerType").asString()).isEqualTo("AI");
        context.getBean(com.disasterinfo.eodiisseo.auth.service.AuthService.class).withdraw(new MemberPrincipal(first,firstSession));
        assertThat(sql.queryForObject("select count(*) from ai_answer where member_id=?",Long.class,first)).isZero();
        assertThat(sql.queryForObject("select count(*) from ai_answer where member_id=?",Long.class,member)).isEqualTo(1);
        assertThat(count("ai_answer_resource")).isPositive();assertThat(send(INDOOR,firstToken).statusCode()).isEqualTo(401);
    }
    @Test void consentRevocationSerializesWithCompletionAndBlocksTheNextRequest()throws Exception{
        var saving=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
        state.atSave=()->{saving.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("SYNTHETIC gate timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}};
        try(var workers=Executors.newFixedThreadPool(2)){
            var request=workers.submit(()->send(INDOOR,token));try{
                assertThat(saving.await(10,TimeUnit.SECONDS)).isTrue();
                var revoked=workers.submit(()->{attempting.countDown();context.getBean(ExternalAiConsentService.class).record(member,session,false);});
                assertThat(attempting.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->revoked.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();assertThat(request.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(200);revoked.get(10,TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
        state.atSave=()->{};assertThat(count("ai_answer")).isEqualTo(1);
        assertThat(send(INDOOR,token).statusCode()).isEqualTo(500);assertThat(count("ai_answer")).isEqualTo(1);
    }
}
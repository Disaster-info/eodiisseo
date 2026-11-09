package com.disasterinfo.eodiisseo.ai.resource.vector;
import com.disasterinfo.eodiisseo.ai.resource.*;
import com.disasterinfo.eodiisseo.ai.resource.adapter.*;
import com.disasterinfo.eodiisseo.ai.resource.entity.OfficialResourceDocument;
import com.disasterinfo.eodiisseo.ai.chat.port.*;
import com.disasterinfo.eodiisseo.auth.repository.*;
import com.disasterinfo.eodiisseo.auth.service.*;
import com.disasterinfo.eodiisseo.auth.security.*;
import com.disasterinfo.eodiisseo.auth.client.SocialIdentityVerifier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Real disposable PostgreSQL transactions + explicit fake vector/embedding. Never claim this is pgvector. */
@EnabledIfEnvironmentVariable(named="EODIISSEO_LOCAL_DB_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Timeout(90)
class RagVectorPipelineIntegrationTest {
    JdbcTemplate admin,sql;String name;com.zaxxer.hikari.HikariDataSource ds;DataSourceTransactionManager manager;TransactionTemplate tx;
    JdbcOfficialResourceStore resources;DatabaseResourceApproval approvals;SessionAccessService sessions;AuthRepository repository;AuthService auth;
    SocialIdentityVerifier social;MemberPrincipal ops;MutableClock clock=new MutableClock();FakeBackend backend;JdbcRagVectorStore store;FakeEmbedding embeddings;
    final Map<UUID,String> plans=new HashMap<>();
    final EmbeddingPort.Model model=new EmbeddingPort.Model("SYNTHETIC-NOT-A-REAL-MODEL","sha256:"+"a".repeat(64),2,1024,"SYNTHETIC-INPUT-1");
    final String body="SYNTHETIC 지진 발생 시 실내에서는 탁자 아래에서 몸을 보호하세요.";
    static class MutableClock extends Clock {Instant now;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
    @BeforeAll void open(){
        String base=System.getenv("EODIISSEO_AUDIT_JDBC_URL");if(!"true".equals(System.getenv("EODIISSEO_AUDIT_DISPOSABLE"))||!"jdbc:postgresql://127.0.0.1:55433/postgres".equals(base))throw new IllegalStateException("Disposable audit cluster only");
        String isolated=System.getenv("EODIISSEO_ISOLATED_VECTOR_JDBC_URL");
        if(isolated!=null){if(!"true".equals(System.getenv("EODIISSEO_ISOLATED_VECTOR_DISPOSABLE"))||!"jdbc:postgresql://127.0.0.1:55434/postgres".equals(isolated))throw new IllegalStateException("Explicit isolated loopback vector instance only");base=isolated;}
        admin=new JdbcTemplate(new DriverManagerDataSource(base,"eodiisseo_audit",""));name="owned_"+UUID.randomUUID().toString().replace("-","");admin.execute("create database "+name);
        ds=new com.zaxxer.hikari.HikariDataSource();ds.setJdbcUrl(base.substring(0,base.length()-"postgres".length())+name);ds.setUsername("eodiisseo_audit");ds.setPassword("");ds.setMaximumPoolSize(4);ds.setMinimumIdle(1);ds.setConnectionTimeout(5000);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/owned-runtime").load().migrate();
        new ResourceDatabasePopulator(new ClassPathResource("ai/rag_vector_metadata.sql")).execute(ds);
        sql=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        resources=new JdbcOfficialResourceStore(Optional.of(ds));repository=new AuthRepository(new OwnedDatabase(sql));sessions=new SessionAccessService(repository);social=mock(SocialIdentityVerifier.class);
        auth=new AuthService(repository,new TokenService("SYNTHETIC-VECTOR-ONLY-0123456789abcdef","test","test",900,clock),social,clock,manager,1209600);
        approvals=new DatabaseResourceApproval(new OwnedDatabase(sql),clock,sessions,auth);
    }
    @BeforeEach void reset(){
        clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);plans.clear();
        for(String table:List.of("rag_test_vector","rag_vector_chunk","rag_vector_group","rag_vector_work","ai_resource_review","owned_resource_import"))sql.update("delete from "+table);
        UUID member=UUID.randomUUID(),session=UUID.randomUUID();repository.createMember(member,"SYNTHETIC",clock.instant());repository.grantUserRole(member,clock.instant());
        sql.update("insert into member_role values(?,'OPS',?)",member,java.sql.Timestamp.from(clock.instant()));
        repository.createSession(session,member,AuthService.hash(UUID.randomUUID().toString()),clock.instant(),clock.instant().plus(Duration.ofDays(14)));ops=new MemberPrincipal(member,session);
        backend=new FakeBackend();store=new JdbcRagVectorStore(sql,manager,backend,clock);embeddings=new FakeEmbedding();
    }
    @AfterEach void noExternalIdentityCalls(){verifyNoInteractions(social);}
    @AfterAll void close(){if(ds!=null)ds.close();if(admin!=null&&name!=null&&name.matches("owned_[a-f0-9]{32}"))admin.execute("drop database "+name+" with (force)");}
    OfficialResourceDocument source(String hazard,String text,boolean approve){
        String title=switch(hazard){case "EARTHQUAKE"->"SYNTHETIC 지진";case "HEAVY_RAIN"->"SYNTHETIC 호우";default->"SYNTHETIC 산불";};UUID id=UUID.randomUUID();
        var doc=new OfficialResourceDocument(id,"행정안전부",title,"https://safekorea.go.kr/synthetic-vector/"+id,"SYNTHETIC",text,clock.instant().minusSeconds(1));resources.append(doc);
        if(approve)tx.executeWithoutResult(s->approvals.review(ops,id,doc.revisionFingerprint(),clock.instant().plusSeconds(600),"SYNTHETIC REVIEW ONLY",true,true));return doc;
    }
    RagChunkGroup group(OfficialResourceDocument doc,String hazard){
        String[] original=RagChunkGroup.BOUNDARY.split(doc.getContentText());var chunks=new ArrayList<RagChunkGroup.Paragraph>();
        for(int i=0;i<original.length;i++)chunks.add(new RagChunkGroup.Paragraph(UUID.randomUUID(),i,original[i],EmbeddingPort.sha(original[i]),"SYNTHETIC HTML paragraph "+i,false));
        var group=new RagChunkGroup(UUID.randomUUID(),doc.getResourceId(),doc.revisionFingerprint(),hazard,List.of("SYNTHETIC REVIEWED SECTION"),"SYNTHETIC-EXTRACT-1",chunks);
        plans.put(group.id(),group.manifestHash());return group;
    }
    ApprovedRagPipeline pipeline(){return new ApprovedRagPipeline(resources,approvals,(id,fp,hash,now)->hash.equals(plans.get(id)),store,embeddings,model,clock);}
    ChatAnswerGenerationPort generator(){return new ChatAnswerGenerationPort(){public boolean isConfigured(){return false;}public GeneratedAnswer generate(GenerationRequest r){throw new AssertionError("No model call");}};}
    ChatOfficialResourceSearchPort exact(){return pipeline().configure(ApprovedRagPipeline.Mode.EXACT,generator()).search();}
    long count(String table){return sql.queryForObject("select count(*) from "+table,Long.class);}
    void revoke(UUID id){tx.executeWithoutResult(s->approvals.revoke(ops,id));}
    class FakeEmbedding implements EmbeddingPort {
        final AtomicInteger calls=new AtomicInteger();Function<Request,Result> handler=r->new Result(r.model(),EmbeddingPort.sha(r.text()),4,true,r.text().contains("엘리베이터")?new double[]{0,1}:new double[]{1,0});
        public boolean networkFree(){return true;}public Result embed(Request r){calls.incrementAndGet();return handler.apply(r);}
    }
    /** JDBC float8[] is a test fake only. SQL approval predicates are real; cosine scoring is Java, NOT pgvector. */
    class FakeBackend implements RagVectorStore.Backend {
        final AtomicInteger puts=new AtomicInteger();int failAt=-1;Runnable afterSearch=()->{};
        public boolean available(){return true;}
        public void put(UUID chunk,EmbeddingPort.Model m,double[] vector){
            int n=puts.incrementAndGet();double[] checked=EmbeddingPort.vector(m,vector);
            sql.execute((ConnectionCallback<Void>)con->{
                var array=con.createArrayOf("float8",Arrays.stream(checked).boxed().toArray());
                try(var ps=con.prepareStatement("insert into rag_test_vector(chunk_id,model_key,embedding_dimension,embedding_values) values(?,?,?,?)")){
                    ps.setObject(1,chunk);ps.setString(2,m.key());ps.setInt(3,n==failAt?m.dimension()+1:m.dimension());ps.setArray(4,array);ps.executeUpdate();
                }finally{array.free();}return null;
            });
        }
        public boolean contains(UUID chunk,EmbeddingPort.Model m){return sql.queryForObject("select count(*) from rag_test_vector where chunk_id=? and model_key=? and embedding_dimension=? and cardinality(embedding_values)=?",Long.class,chunk,m.key(),m.dimension(),m.dimension())==1;}
        public List<RagVectorStore.Hit> search(String hazard,EmbeddingPort.Model m,double[] query,Instant now,int limit){
            String base=JdbcRagVectorStore.ELIGIBLE.replace("where g.hazard_type=?","join rag_test_vector v on v.chunk_id=c.chunk_id and v.model_key=c.model_key where g.hazard_type=?");
            var hits=sql.query("select c.group_id,c.chunk_id,v.embedding_values "+base+" and v.embedding_dimension="+m.dimension(),(rs,n)->{
                var a=rs.getArray(3);Object[] values=(Object[])a.getArray();double[] vec=new double[values.length];for(int i=0;i<values.length;i++)vec[i]=((Number)values[i]).doubleValue();a.free();
                EmbeddingPort.vector(m,vec);double dot=0;for(int i=0;i<vec.length;i++)dot+=vec[i]*query[i];return new RagVectorStore.Hit((UUID)rs.getObject(1),(UUID)rs.getObject(2),1-dot);
            },JdbcRagVectorStore.filters(hazard,m,now));afterSearch.run();
            return hits.stream().sorted(Comparator.comparingDouble(RagVectorStore.Hit::distance).thenComparing(RagVectorStore.Hit::chunk)).limit(limit).toList();
        }
    }
    @Test void fakeVectorStorePersistsApprovedExactParagraphAndRestartIsIdempotent(){
        var doc=source("EARTHQUAKE",body,true);var group=group(doc,"EARTHQUAKE");assertThat(pipeline().index(group,Duration.ofSeconds(5))).isEqualTo("READY");
        assertThat(exact().search("지진 실내 행동요령").resources()).singleElement().satisfies(r->{assertThat(r.resourceId()).isEqualTo(doc.getResourceId());assertThat(r.content()).isEqualTo(body);});
        int calls=embeddings.calls.get();store=new JdbcRagVectorStore(sql,manager,new FakeBackend(),clock);
        assertThat(pipeline().index(group,Duration.ofSeconds(5))).isEqualTo("DUPLICATE");assertThat(embeddings.calls.get()).isEqualTo(calls);
        assertThat(count("rag_vector_chunk")).isEqualTo(1);assertThat(count("rag_test_vector")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"EARTHQUAKE","HEAVY_RAIN","WILDFIRE"})
    void hazardScopeIsAppliedBeforeFakeRanking(String hazard){
        var texts=Map.of("EARTHQUAKE",body,"HEAVY_RAIN","SYNTHETIC 호우 시 지하차도에 진입하지 마세요.","WILDFIRE","SYNTHETIC 산불 시 대피 안내를 확인하세요.");
        UUID wanted=null;for(var entry:texts.entrySet()){var doc=source(entry.getKey(),entry.getValue(),true);if(entry.getKey().equals(hazard))wanted=doc.getResourceId();assertThat(pipeline().index(group(doc,entry.getKey()),Duration.ofSeconds(5))).isEqualTo("READY");}
        String question=switch(hazard){case "EARTHQUAKE"->"지진 실내 행동요령";case "HEAVY_RAIN"->"호우 지하차도 진입해도 되나요?";default->"산불 대피 행동요령";};
        UUID expected=wanted;assertThat(exact().search(question).resources()).singleElement().satisfies(r->assertThat(r.resourceId()).isEqualTo(expected));
    }
    @Test void publicationAndChunkPlanApprovalsAreBothRequiredBeforeEmbedding(){
        var doc=source("EARTHQUAKE",body,false);var group=group(doc,"EARTHQUAKE");assertThat(pipeline().index(group,Duration.ofSeconds(5))).isEqualTo("UNAPPROVED_OR_INVALID_SOURCE");
        assertThat(embeddings.calls.get()).isZero();assertThat(count("rag_vector_work")).isZero();
        tx.executeWithoutResult(s->approvals.review(ops,doc.getResourceId(),doc.revisionFingerprint(),clock.instant().plusSeconds(600),"SYNTHETIC",true,true));plans.clear();
        assertThat(pipeline().index(group,Duration.ofSeconds(5))).isEqualTo("UNAPPROVED_OR_INVALID_SOURCE");assertThat(embeddings.calls.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"REVOKE","EXPIRE","RIGHTS","REPLACED","CONTENT","PUBLISHER","PLAN"})
    void staleOrUnapprovedCandidatesNeverReachQueryEmbedding(String mode){
        var doc=source("EARTHQUAKE",body,true);var g=group(doc,"EARTHQUAKE");assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("READY");
        switch(mode){
            case "REVOKE"->revoke(doc.getResourceId());case "EXPIRE"->clock.now=clock.now.plusSeconds(600);
            case "RIGHTS"->sql.update("update ai_resource_review set approved_public_ai_evidence=false where resource_id=?",doc.getResourceId());
            case "CONTENT"->sql.update("update official_resource set content_text=? where resource_id=?",body+" CHANGED",doc.getResourceId());
            case "PUBLISHER"->sql.update("update official_resource set agency_name='SYNTHETIC OTHER' where resource_id=?",doc.getResourceId());
            case "PLAN"->plans.clear();
            case "REPLACED"->sql.update("insert into owned_resource_import(resource_id,source_url,content_sha256,raw_body,media_type,fetched_at,expires_at,superseded_at) values(?,?,?,?,?,?,?,?)",doc.getResourceId(),doc.getSourceUrl(),"b".repeat(64),new byte[]{1},"SYNTHETIC",java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().plusSeconds(600)),java.sql.Timestamp.from(clock.instant()));
        }
        int calls=embeddings.calls.get();assertThat(exact().search("지진 실내 행동요령").resources()).isEmpty();assertThat(embeddings.calls.get()).isEqualTo(calls);
    }
    @ParameterizedTest @ValueSource(strings={"DOCUMENT","QUERY","SEARCH"})
    void revocationDuringEachStageCannotPublishAnAnswer(String stage){
        var doc=source("EARTHQUAKE",body,true);var g=group(doc,"EARTHQUAKE");var normal=embeddings.handler;
        if(stage.equals("DOCUMENT")){
            embeddings.handler=r->{revoke(doc.getResourceId());return normal.apply(r);};
            assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("INDEXING_FAILED");assertThat(count("rag_vector_chunk")).isZero();assertThat(count("rag_test_vector")).isZero();
        }else{
            assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("READY");
            if(stage.equals("QUERY"))embeddings.handler=r->{revoke(doc.getResourceId());return normal.apply(r);};else backend.afterSearch=()->revoke(doc.getResourceId());
            assertThat(exact().search("지진 실내 행동요령").resources()).isEmpty();
        }
    }
    @ParameterizedTest @ValueSource(strings={"MISSING_CHUNK","MISSING_VECTOR","TEXT","MODEL"})
    void tamperedOrIncompleteContextIsRejected(String mode){
        var doc=source("EARTHQUAKE",body+"\n\nSYNTHETIC 지진 실내에서는 예외 조건도 확인하세요.",true);var g=group(doc,"EARTHQUAKE");assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("READY");
        UUID second=g.paragraphs().get(1).id();
        switch(mode){case "MISSING_CHUNK"->sql.update("delete from rag_vector_chunk where chunk_id=?",second);case "MISSING_VECTOR"->sql.update("delete from rag_test_vector where chunk_id=?",second);case "TEXT"->sql.update("update rag_vector_chunk set paragraph_text='SYNTHETIC TAMPER' where chunk_id=?",second);case "MODEL"->sql.update("update rag_vector_group set embedding_model_digest=? where group_id=?","sha256:"+"b".repeat(64),g.id());}
        int calls=embeddings.calls.get();assertThat(exact().search("지진 실내 행동요령").resources()).isEmpty();assertThat(embeddings.calls.get()).isEqualTo(calls);
    }
    @Test void databaseFailureRollsBackChunksVectorsAndReadyStateThenRetrySucceeds(){
        var doc=source("EARTHQUAKE",body+"\n\nSYNTHETIC 지진 실내에서는 예외 조건도 확인하세요.",true);var g=group(doc,"EARTHQUAKE");backend.failAt=2;
        assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("INDEXING_FAILED");
        assertThat(count("rag_vector_group")).isZero();assertThat(count("rag_vector_chunk")).isZero();assertThat(count("rag_test_vector")).isZero();
        assertThat(sql.queryForObject("select state from rag_vector_work",String.class)).isEqualTo("FAILED");
        backend.failAt=-1;assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("READY");assertThat(count("rag_test_vector")).isEqualTo(2);
    }
    @Test void generationFailureHasBoundedRetriesAndNoInventedVectors(){
        var doc=source("EARTHQUAKE",body,true);var g=group(doc,"EARTHQUAKE");embeddings.handler=r->{throw new IllegalStateException("SYNTHETIC PRIVATE DETAIL");};
        for(int i=0;i<3;i++)assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("INDEXING_FAILED");
        assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("RETRY_LIMIT");assertThat(embeddings.calls.get()).isEqualTo(3);assertThat(count("rag_test_vector")).isZero();
    }
    @Test void expiredLeaseCannotBeCompletedOrFailedByTheOldClaim(){
        var doc=source("EARTHQUAKE",body,true);var g=group(doc,"EARTHQUAKE");var old=store.claim(g,model);assertThat(store.claim(g,model).state()).isEqualTo("BUSY");
        clock.now=clock.now.plusSeconds(31);var next=store.claim(g,model);
        assertThatThrownBy(()->store.commit(g,model,old,List.of(new double[]{1,0}),()->true)).hasMessage("INDEX_LEASE_LOST");
        store.fail(g,model,old,"SYNTHETIC_OLD_FAILURE");assertThat(sql.queryForObject("select claim_id from rag_vector_work",UUID.class)).isEqualTo(next.token());
        store.fail(g,model,next,"SYNTHETIC_CLEANUP");
    }
    @Test void leaseExpiryWhileApprovalIsCheckedRollsBackEveryWrite(){
        var doc=source("EARTHQUAKE",body,true);var g=group(doc,"EARTHQUAKE");var claim=store.claim(g,model);
        assertThatThrownBy(()->store.commit(g,model,claim,List.of(new double[]{1,0}),()->{clock.now=clock.now.plusSeconds(31);return true;})).hasMessage("INDEX_LEASE_LOST");
        assertThat(count("rag_vector_group")).isZero();assertThat(count("rag_vector_chunk")).isZero();assertThat(count("rag_test_vector")).isZero();
        store.fail(g,model,claim,"EXPIRED");
    }
    @Test void otherModelSpaceAndDefaultLexicalModeCannotMixVectors(){
        var doc=source("EARTHQUAKE",body,true);var g=group(doc,"EARTHQUAKE");assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("READY");
        var other=new EmbeddingPort.Model(model.id(),"sha256:"+"b".repeat(64),3,1024,model.inputVersion());
        var p=new ApprovedRagPipeline(resources,approvals,(id,fp,h,now)->true,store,embeddings,other,clock);
        int calls=embeddings.calls.get();assertThat(p.configure(ApprovedRagPipeline.Mode.EXACT,generator()).search().search("지진 실내 행동요령").resources()).isEmpty();assertThat(embeddings.calls.get()).isEqualTo(calls);
        assertThat(pipeline().configure(null,generator()).search()).isInstanceOf(ResourceService.class);
        assertThat(pipeline().configure(null,generator()).search().search("지진 실내 행동요령").resources()).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"지진과 산불 대피 방법","현재 내 위치의 지진 위험 여부","호우 대피소로 안전한 경로 알려줘","근거 없는 음식 질문"})
    void unsafeOrUnsupportedQuestionsDoNotAcquireFakeSemanticSuccess(String question){
        var doc=source("HEAVY_RAIN","SYNTHETIC 호우 시 침수된 지하차도에 진입하지 마세요.",true);assertThat(pipeline().index(group(doc,"HEAVY_RAIN"),Duration.ofSeconds(5))).isEqualTo("READY");
        assertThat(exact().search(question).resources()).isEmpty();
    }
    @Test void wrongHazardOrLocatorAndParagraphMismatchNeverIndex(){
        var doc=source("WILDFIRE","SYNTHETIC 산불 시 대피 안내를 확인하세요.",true);var wrong=group(doc,"EARTHQUAKE");
        assertThat(pipeline().index(wrong,Duration.ofSeconds(5))).isEqualTo("UNAPPROVED_OR_INVALID_SOURCE");
        assertThatThrownBy(()->new RagChunkGroup.Paragraph(UUID.randomUUID(),0,body,EmbeddingPort.sha(body),null,false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(embeddings.calls.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"호우가 내릴 때 지하차도에 접근해도 되나요?","호우 시 지하차도 접근 방법"})
    void semanticHitIsNotDiscardedByLexicalWordMatching(String question){
        var doc=source("HEAVY_RAIN","SYNTHETIC 호우 안내: 침수된 지하차도에는 진입하지 마세요.",true);var g=group(doc,"HEAVY_RAIN");
        var lexical=pipeline().configure(ApprovedRagPipeline.Mode.LEXICAL,generator()).search();
        assertThat(lexical.search(question).resources()).isEmpty(); // Preserve the production lexical baseline.
        assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("READY");
        assertThat(exact().search(question).resources()).singleElement().satisfies(r->{assertThat(r.resourceId()).isEqualTo(doc.getResourceId());assertThat(r.content()).isEqualTo(doc.getContentText());});
        revoke(doc.getResourceId());assertThat(exact().search(question).resources()).isEmpty();
        assertThat(lexical.search(question).resources()).isEmpty();
    }
    @Test void distantFakeHitIsNotEvidenceAndSearchDatabaseFailureIsClosed(){
        var doc=source("HEAVY_RAIN","SYNTHETIC 호우 지하차도 진입하지 마세요.",true);assertThat(pipeline().index(group(doc,"HEAVY_RAIN"),Duration.ofSeconds(5))).isEqualTo("READY");
        var normal=embeddings.handler;embeddings.handler=r->r.purpose()==EmbeddingPort.Purpose.QUERY?new EmbeddingPort.Result(r.model(),EmbeddingPort.sha(r.text()),4,true,new double[]{0,1}):normal.apply(r);
        assertThat(exact().search("호우 지하차도 접근해도 되나요?").resources()).isEmpty();
        embeddings.handler=normal;backend.afterSearch=()->sql.queryForObject("select count(*) from deliberately_missing_synthetic_table",Long.class);
        assertThat(exact().search("호우 지하차도 접근해도 되나요?").resources()).isEmpty();assertThat(count("rag_vector_group")).isEqualTo(1);
    }
    @Test void planRevocationAfterRankingAndMixedHazardParagraphsAreRejected(){
        var doc=source("HEAVY_RAIN","SYNTHETIC 호우 지하차도 진입하지 마세요.",true);assertThat(pipeline().index(group(doc,"HEAVY_RAIN"),Duration.ofSeconds(5))).isEqualTo("READY");
        backend.afterSearch=plans::clear;assertThat(exact().search("호우 지하차도 접근해도 되나요?").resources()).isEmpty();
        var mixed=source("HEAVY_RAIN","SYNTHETIC 호우 안내와 산불 대피 안내를 확인하세요.",true);
        int before=embeddings.calls.get();assertThat(pipeline().index(group(mixed,"HEAVY_RAIN"),Duration.ofSeconds(5))).isEqualTo("UNAPPROVED_OR_INVALID_SOURCE");assertThat(embeddings.calls.get()).isEqualTo(before);
    }
    @Test void reviewedContextHeadingIsKeptAlongsideExactInstruction(){
        var doc=source("HEAVY_RAIN","SYNTHETIC 호우 지하차도 조건\n\nSYNTHETIC 침수된 지하차도에는 진입하지 마세요.",true);var initial=group(doc,"HEAVY_RAIN");var heading=initial.paragraphs().getFirst();
        var g=new RagChunkGroup(initial.id(),initial.resourceId(),initial.fingerprint(),initial.hazard(),initial.sectionPath(),initial.extractionVersion(),List.of(new RagChunkGroup.Paragraph(heading.id(),heading.ordinal(),heading.text(),heading.hash(),heading.locator(),true),initial.paragraphs().get(1)));
        plans.put(g.id(),g.manifestHash());assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("READY");
        assertThat(exact().search("호우 지하차도 접근해도 되나요?").resources()).singleElement().satisfies(r->assertThat(r.content()).isEqualTo(doc.getContentText()));
        sql.update("delete from rag_vector_chunk where chunk_id=?",heading.id());assertThat(exact().search("호우 지하차도 접근해도 되나요?").resources()).isEmpty();
    }
    @Test void sharedEvaluationRunsBothStrategiesAgainstTheSameSyntheticApprovedCorpus(){
        for(var fixture:SyntheticRagEvaluation.documents()){
            var doc=new OfficialResourceDocument(fixture.id(),"행정안전부",fixture.title(),"https://safekorea.go.kr/synthetic-evaluation/"+fixture.id(),"SYNTHETIC",String.join("\n\n",fixture.paragraphs()),clock.instant().minusSeconds(1));resources.append(doc);
            tx.executeWithoutResult(status->approvals.review(ops,doc.getResourceId(),doc.revisionFingerprint(),clock.instant().plusSeconds(600),"SYNTHETIC EVALUATION ONLY",true,true));
            assertThat(pipeline().index(group(doc,fixture.hazard()),Duration.ofSeconds(5))).isEqualTo("READY");
        }
        var metrics=SyntheticRagEvaluation.compare(Map.of("Lexical",pipeline().configure(ApprovedRagPipeline.Mode.LEXICAL,generator()).search(),"FakeVectorExact",exact()));
        assertThat(metrics).hasSize(2);metrics.values().forEach(m->{assertThat(m.questions()).isEqualTo(23);assertThat(m.failures()).isZero();assertThat(m.wrongHazardRate()).isZero();assertThat(m.unsupportedResponseRate()).isZero();});
        assertThat(metrics.get("FakeVectorExact").missingContextRate()).isZero();
        // No assertion of semantic accuracy or latency improvement is meaningful with this Fake Embedding.
    }
    @Test void pgvectorAbsenceIsExplicitlyFailClosed(){
        var real=new PgVectorBackend(sql);
        if(!real.available())assertThatThrownBy(()->real.search("EARTHQUAKE",model,new double[]{1,0},clock.instant(),1)).hasMessage("PGVECTOR_EXTENSION_REQUIRED");
        assertThat(sql.queryForObject("select inet_server_port()",Integer.class)).isIn(55433,55434);
    }
    @Test void realPgvectorExactSqlWhenExtensionIsAvailable(){
        boolean supplied=Boolean.TRUE.equals(sql.queryForObject("select exists(select 1 from pg_available_extensions where name='vector')",Boolean.class));
        Assumptions.assumeTrue(supplied,"BLOCKED: pgvector is not installed in the permitted 55433 cluster; no package installation attempted");
        try{sql.execute("create extension if not exists vector");}catch(RuntimeException denied){Assumptions.assumeTrue(false,"BLOCKED: disposable DB cannot create vector extension");}
        sql.execute("create table rag_vector_embedding(chunk_id uuid,model_key char(64),embedding_dimension integer,embedding vector,primary key(chunk_id,model_key),foreign key(chunk_id,model_key) references rag_vector_chunk on delete cascade,check(vector_dims(embedding)=embedding_dimension))");
        try{
            store=new JdbcRagVectorStore(sql,manager,new PgVectorBackend(sql),clock);var doc=source("EARTHQUAKE",body,true);var g=group(doc,"EARTHQUAKE");
            assertThat(pipeline().index(g,Duration.ofSeconds(5))).isEqualTo("READY");assertThat(exact().search("지진 실내 행동요령").resources()).hasSize(1);
        }finally{sql.execute("drop table rag_vector_embedding");}
    }
}
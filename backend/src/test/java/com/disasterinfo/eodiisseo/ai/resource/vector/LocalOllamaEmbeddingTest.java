package com.disasterinfo.eodiisseo.ai.resource.vector;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** All HTTP goes to an ephemeral in-process stub, never an actual Ollama. */
class LocalOllamaEmbeddingTest {
    final EmbeddingPort.Model model=new EmbeddingPort.Model("qwen3-embedding:0.6b","sha256:"+"a".repeat(64),2,128,LocalOllamaEmbedding.INPUT_VERSION);
    final Clock clock=Clock.systemUTC();final String text="SYNTHETIC 호우 지하차도 질문";HttpServer server;ExecutorService executor;URI base;
    final AtomicInteger calls=new AtomicInteger(),embeds=new AtomicInteger();volatile String mode="OK",posted;volatile boolean changed;
    static final JsonMapper JSON=JsonMapper.builder().build();
    @BeforeEach void open()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",this::handle);server.start();base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void close(){server.stop(0);executor.shutdownNow();}
    void handle(HttpExchange exchange){
        calls.incrementAndGet();try{
            if(exchange.getRequestURI().getPath().equals("/api/tags")){
                assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                String digest=(changed||mode.equals("PRE_DIGEST"))?"b".repeat(64):"a".repeat(64);
                reply(exchange,200,mode.equals("MISSING")?"{\"models\":[]}":"{\"models\":[{\"name\":\"qwen3-embedding:0.6b\",\"digest\":\""+digest+"\"}]}");return;
            }
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/embed");assertThat(exchange.getRequestMethod()).isEqualTo("POST");embeds.incrementAndGet();posted=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(mode.equals("POST_DIGEST"))changed=true;if(mode.equals("SLOW"))Thread.sleep(500);
            if(mode.equals("ERROR")){reply(exchange,400,"PRIVATE_SYNTHETIC_ERROR");return;}
            if(mode.equals("REDIRECT")){exchange.getResponseHeaders().add("Location","http://example.invalid/never-follow");reply(exchange,307,"");return;}
            if(mode.equals("OVERSIZE")){reply(exchange,200,"x".repeat(262145));return;}
            if(mode.equals("MALFORMED")){reply(exchange,200,"PRIVATE_SYNTHETIC_NOT_JSON");return;}
            String values=switch(mode){case "DIMENSION"->"[1]";case "ZERO"->"[0,0]";case "NON_UNIT"->"[2,0]";default->"[1,0]";};
            reply(exchange,200,"{\"model\":\""+(mode.equals("MODEL")?"OTHER":"qwen3-embedding:0.6b")+"\",\"embeddings\":["+values+"],\"prompt_eval_count\":"+(mode.equals("TOKENS")?129:4)+"}");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(Exception closed){/* timeout cancellation closes the stub exchange */}finally{exchange.close();}
    }
    void reply(HttpExchange exchange,int status,String body)throws Exception{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
    EmbeddingPort.Request request(EmbeddingPort.Purpose purpose){return new EmbeddingPort.Request(model,purpose,text,false,clock.instant().plusSeconds(3));}
    LocalOllamaEmbedding adapter(int budget){return new LocalOllamaEmbedding(base,model,new LocalOllamaEmbedding.SyntheticPermit(true,"SYNTHETIC STUB TEST ONLY",model,Set.of(LocalOllamaEmbedding.AllowedInput.of(EmbeddingPort.Purpose.QUERY,text),LocalOllamaEmbedding.AllowedInput.of(EmbeddingPort.Purpose.DOCUMENT,text)),budget,clock.instant().plusSeconds(30)),clock);}
    @Test void defaultDisabledAndUnapprovedInputCannotReachTheServer(){
        var disabled=new LocalOllamaEmbedding(base,model,null,clock);assertThat(disabled.networkFree()).isFalse();assertThat(disabled.configured()).isFalse();
        assertThatThrownBy(()->EmbeddingPort.generate(disabled,request(EmbeddingPort.Purpose.QUERY),clock)).hasMessage("NETWORK_EMBEDDING_DISABLED");
        var adapter=adapter(1);var privateInput=new EmbeddingPort.Request(model,EmbeddingPort.Purpose.QUERY,"PRIVATE NOT ALLOWLISTED",false,clock.instant().plusSeconds(1));
        assertThatThrownBy(()->adapter.embed(privateInput)).hasMessage("LOCAL_SYNTHETIC_PERMISSION_REQUIRED");assertThat(calls.get()).isZero();
        assertThatThrownBy(()->new LocalOllamaEmbedding.SyntheticPermit(false,"NO APPROVAL",model,Set.of(LocalOllamaEmbedding.AllowedInput.of(EmbeddingPort.Purpose.QUERY,text)),1,clock.instant().plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"http://example.invalid:11434","http://localhost:11434","https://127.0.0.1:11434","http://user@127.0.0.1:11434","http://127.0.0.1:11434/api","http://127.0.0.1:11434/?token=x"})
    void onlyExplicitUncredentialedLoopbackOriginIsAllowed(String address){assertThatThrownBy(()->new LocalOllamaEmbedding(URI.create(address),model,null,clock)).isInstanceOf(IllegalArgumentException.class);assertThat(calls.get()).isZero();}
    @Test void queryAndDocumentTemplatesTruncateAndDigestAreBoundToTheRequest()throws Exception{
        var adapter=adapter(2);assertThat(EmbeddingPort.generate(adapter,request(EmbeddingPort.Purpose.QUERY),clock)).containsExactly(1,0);
        var query=JSON.readTree(posted);assertThat(query.path("truncate").asBoolean()).isFalse();assertThat(query.path("dimensions").asInt()).isEqualTo(2);
        assertThat(query.path("input").asString()).isEqualTo("Instruct: Given a search query, retrieve relevant disaster safety guidance.\nQuery: "+text);
        assertThat(EmbeddingPort.generate(adapter,request(EmbeddingPort.Purpose.DOCUMENT),clock)).containsExactly(1,0);assertThat(JSON.readTree(posted).path("input").asString()).isEqualTo(text);
        assertThat(calls.get()).isEqualTo(6);assertThat(embeds.get()).isEqualTo(2);assertThat(adapter.configured()).isFalse();
        assertThatThrownBy(()->adapter.embed(request(EmbeddingPort.Purpose.QUERY))).hasMessage("LOCAL_SYNTHETIC_PERMISSION_REQUIRED");assertThat(calls.get()).isEqualTo(6);
    }
    @ParameterizedTest @ValueSource(strings={"PRE_DIGEST","POST_DIGEST","MISSING","DIMENSION","ZERO","NON_UNIT","MODEL","TOKENS","MALFORMED","OVERSIZE","ERROR","REDIRECT"})
    void badModelResponseCannotBeRepairedOrLeaked(String response){
        mode=response;var adapter=adapter(1);assertThatThrownBy(()->adapter.embed(request(EmbeddingPort.Purpose.QUERY))).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("PRIVATE_SYNTHETIC").hasMessageNotContaining(text);
        assertThat(adapter.configured()).isFalse();assertThat(calls.get()).isLessThanOrEqualTo(3);if(response.equals("PRE_DIGEST")||response.equals("MISSING"))assertThat(embeds.get()).isZero();
    }
    @Test void timeoutIsBoundedWithoutRetryOrDownload(){
        mode="SLOW";var adapter=adapter(1);var request=new EmbeddingPort.Request(model,EmbeddingPort.Purpose.QUERY,text,false,clock.instant().plusMillis(150));long start=System.nanoTime();
        assertThatThrownBy(()->EmbeddingPort.generate(adapter,request,clock)).isInstanceOf(IllegalStateException.class);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));assertThat(embeds.get()).isLessThanOrEqualTo(1);
    }
    @Test void expiredPermitAndDifferentModelNeverSendMetadata(){
        var permit=new LocalOllamaEmbedding.SyntheticPermit(true,"SYNTHETIC EXPIRED",model,Set.of(LocalOllamaEmbedding.AllowedInput.of(EmbeddingPort.Purpose.QUERY,text)),1,clock.instant().minusSeconds(1));
        var adapter=new LocalOllamaEmbedding(base,model,permit,clock);assertThat(adapter.configured()).isFalse();assertThatThrownBy(()->adapter.embed(request(EmbeddingPort.Purpose.QUERY))).hasMessage("LOCAL_SYNTHETIC_PERMISSION_REQUIRED");assertThat(calls.get()).isZero();
    }
}
package com.disasterinfo.eodiisseo.ai.resource.vector;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

/** No Bean or default activation. Only explicitly reviewed synthetic input hashes may reach loopback. */
public final class LocalOllamaEmbedding implements EmbeddingPort {
    public static final String INPUT_VERSION="qwen3-retrieval-v1";
    public record AllowedInput(Purpose purpose,String hash){
        public AllowedInput{if(purpose==null||hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("SYNTHETIC_INPUT_ALLOWLIST_REQUIRED");}
        public static AllowedInput of(Purpose purpose,String text){return new AllowedInput(purpose,EmbeddingPort.sha(text));}
    }
    /** Caller must obtain explicit human approval for this exact model/input set/budget/expiry first. */
    public static final class SyntheticPermit {
        private final String modelKey;private final Set<AllowedInput> inputs;private final Instant expires;private final AtomicInteger remaining;
        public SyntheticPermit(boolean humanApproved,String approvalReference,Model model,Set<AllowedInput> inputs,int maximumCalls,Instant expires){
            if(!humanApproved||approvalReference==null||approvalReference.isBlank()||inputs==null||inputs.isEmpty()||inputs.size()>1000||maximumCalls<1||maximumCalls>1000||expires==null)throw new IllegalArgumentException("EXPLICIT_SYNTHETIC_APPROVAL_REQUIRED");
            this.modelKey=model.key();this.inputs=Set.copyOf(inputs);this.remaining=new AtomicInteger(maximumCalls);this.expires=expires;
        }
        boolean ready(Model m,Clock clock){return modelKey.equals(m.key())&&clock.instant().isBefore(expires)&&remaining.get()>0;}
        boolean allows(Request r,Clock clock){return ready(r.model(),clock)&&inputs.contains(AllowedInput.of(r.purpose(),r.text()));}
        void consume(Request r,Clock clock){if(!allows(r,clock)||remaining.getAndUpdate(n->Math.max(0,n-1))<=0)throw new IllegalStateException("LOCAL_SYNTHETIC_PERMISSION_REQUIRED");}
        @Override public String toString(){return "SyntheticPermit[inputs=<redacted>]";}
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final URI base;private final Model model;private final SyntheticPermit permit;private final Clock clock;private final HttpClient client;
    public LocalOllamaEmbedding(URI base,Model model,SyntheticPermit permit,Clock clock){
        if(base==null||!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getPort()<1||base.getPort()>65535
            ||base.getRawUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null||!(base.getPath().isEmpty()||base.getPath().equals("/")))throw new IllegalArgumentException("EXPLICIT_IPV4_LOOPBACK_ONLY");
        if(!"qwen3-embedding:0.6b".equals(model.id())||!INPUT_VERSION.equals(model.inputVersion())||model.dimension()>1024||model.maxTokens()>32768)throw new IllegalArgumentException("QWEN_RETRIEVAL_CONTRACT_REQUIRED");
        this.base=base;this.model=model;this.permit=permit;this.clock=Objects.requireNonNull(clock);
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress sa,IOException failure){}}).build();
    }
    public boolean configured(){return permit!=null&&permit.ready(model,clock);}
    public boolean permits(Request r){return permit!=null&&model.equals(r.model())&&permit.allows(r,clock);}
    public Result embed(Request r){
        if(!permits(r))throw new IllegalStateException("LOCAL_SYNTHETIC_PERMISSION_REQUIRED");
        permit.consume(r,clock); // A failed call consumes budget as well. No retries or download endpoints.
        try{
            Instant deadline=Collections.min(List.of(r.deadline(),permit.expires,clock.instant().plusSeconds(10)));
            verifyDigest(deadline);
            String input=r.purpose()==Purpose.QUERY?"Instruct: Given a search query, retrieve relevant disaster safety guidance.\nQuery: "+r.text():r.text();
            var request=Map.of("model",model.id(),"input",input,"truncate",false,"dimensions",model.dimension(),"keep_alive",0);
            JsonNode response=exchange("/api/embed",JSON.writeValueAsBytes(request),deadline);
            if(!model.id().equals(response.path("model").asString()))throw new IllegalStateException("MODEL_RESPONSE_MISMATCH");
            var vectors=response.path("embeddings");if(!vectors.isArray()||vectors.size()!=1||!vectors.get(0).isArray())throw new IllegalStateException("EMBEDDING_RESPONSE");
            var array=vectors.get(0);double[] vector=new double[array.size()];for(int n=0;n<vector.length;n++){if(!array.get(n).isNumber())throw new IllegalStateException("EMBEDDING_RESPONSE");vector[n]=array.get(n).asDouble();}
            var tokens=response.path("prompt_eval_count");if(!tokens.isIntegralNumber()||!tokens.canConvertToInt()||tokens.asInt()<1||tokens.asInt()>model.maxTokens())throw new IllegalStateException("TOKEN_CONTRACT");
            EmbeddingPort.vector(model,vector);verifyDigest(deadline);
            if(!permitsAfterConsumption(r)||!clock.instant().isBefore(r.deadline()))throw new IllegalStateException("LOCAL_PERMISSION_EXPIRED");
            return new Result(model,EmbeddingPort.sha(r.text()),tokens.asInt(),true,vector);
        }catch(RuntimeException safe){String code=safe.getMessage();throw new IllegalStateException(code!=null&&code.matches("[A-Z][A-Z0-9_]{1,80}")?code:"LOCAL_EMBEDDING_FAILED");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("EMBEDDING_INTERRUPTED");}catch(Exception failure){throw new IllegalStateException("LOCAL_EMBEDDING_FAILED");}
    }
    private boolean permitsAfterConsumption(Request r){return permit!=null&&model.equals(r.model())&&clock.instant().isBefore(permit.expires)&&permit.inputs.contains(AllowedInput.of(r.purpose(),r.text()));}
    private void verifyDigest(Instant deadline)throws Exception{
        var entries=exchange("/api/tags",null,deadline).path("models");int found=0;
        if(!entries.isArray())throw new IllegalStateException("MODEL_METADATA_REQUIRED");
        for(var entry:entries)if(model.id().equals(entry.path("name").asString())){
            String digest=entry.path("digest").asString();if(digest!=null&&!digest.startsWith("sha256:"))digest="sha256:"+digest;
            if(!model.digest().equals(digest))throw new IllegalStateException("MODEL_DIGEST_CHANGED");found++;
        }
        if(found!=1)throw new IllegalStateException("PINNED_MODEL_NOT_INSTALLED");
    }
    private JsonNode exchange(String path,byte[] body,Instant deadline)throws Exception{
        Duration budget=Duration.between(clock.instant(),deadline);if(budget.isNegative()||budget.isZero())throw new IllegalStateException("EMBEDDING_TIMEOUT");
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(budget).header("Accept","application/json");
        if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        var future=client.sendAsync(builder.build(),info->new BoundedBody());
        try{
            var response=future.get(budget.toNanos(),TimeUnit.NANOSECONDS);
            if(response.statusCode()!=200)throw new IllegalStateException("LOCAL_HTTP_STATUS_"+response.statusCode());
            return JSON.readTree(response.body());
        }catch(TimeoutException failure){throw new IllegalStateException("EMBEDDING_TIMEOUT");}
        finally{future.cancel(true);}
    }
    /** Bound response memory as well as request wall time; error bodies are never logged. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var b:buffers){if(bytes.size()+b.remaining()>262144){subscription.cancel();result.completeExceptionally(new IllegalStateException("LOCAL_RESPONSE_TOO_LARGE"));return;}byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}subscription.request(1);
        }
        public void onError(Throwable failure){result.completeExceptionally(new IllegalStateException("LOCAL_RESPONSE_FAILED"));}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
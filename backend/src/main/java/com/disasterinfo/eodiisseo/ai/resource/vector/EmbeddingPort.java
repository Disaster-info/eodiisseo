package com.disasterinfo.eodiisseo.ai.resource.vector;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** No HTTP/model implementation is registered. This phase accepts explicitly network-free test ports only. */
public interface EmbeddingPort {
    default boolean networkFree(){return false;}
    default boolean configured(){return networkFree();}
    default boolean permits(Request request){return networkFree();}
    Result embed(Request request) throws Exception;
    enum Purpose { QUERY, DOCUMENT }
    record Model(String id,String digest,int dimension,int maxTokens,String inputVersion) {
        public Model {
            if(id==null||id.isBlank()||id.length()>150||digest==null||!digest.matches("sha256:[a-f0-9]{64}")
                ||dimension<1||dimension>4096||maxTokens<1||inputVersion==null||inputVersion.isBlank()||inputVersion.length()>100)
                throw new IllegalArgumentException("Verified model identity and observed vector contract required");
        }
        public String key(){return sha(id+"\n"+digest+"\n"+dimension+"\n"+maxTokens+"\n"+inputVersion);}
    }
    record Request(Model model,Purpose purpose,String text,boolean truncate,Instant deadline) {
        public Request {if(model==null||purpose==null||text==null||text.isBlank()||text.length()>20000||truncate||deadline==null)throw new IllegalArgumentException("Bounded non-truncating embedding request required");}
        @Override public String toString(){return "EmbeddingRequest[purpose="+purpose+", text=<redacted>]";}
    }
    record Result(Model observedModel,String inputHash,int tokens,boolean truncationDisabled,double[] values) {
        public Result {values=values==null?null:values.clone();}
        @Override public double[] values(){return values==null?null:values.clone();}
        @Override public String toString(){return "EmbeddingResult[values=<redacted>]";}
    }
    static double[] vector(Model model,double[] values){
        if(values==null||values.length!=model.dimension())throw new IllegalArgumentException("VECTOR_DIMENSION");
        double norm=0;for(double v:values){if(!Double.isFinite(v))throw new IllegalArgumentException("VECTOR_NONFINITE");norm+=v*v;}
        if(!Double.isFinite(norm)||norm==0||Math.abs(Math.sqrt(norm)-1)>1e-4)throw new IllegalArgumentException("VECTOR_NOT_UNIT_LENGTH");
        return values.clone();
    }
    static double[] generate(EmbeddingPort port,Request request,Clock clock){
        if(port==null||!port.configured()||!port.permits(request))throw new IllegalStateException("NETWORK_EMBEDDING_DISABLED");
        long budget=Duration.between(clock.instant(),request.deadline()).toNanos();if(budget<=0)throw new IllegalStateException("EMBEDDING_TIMEOUT");
        var executor=Executors.newVirtualThreadPerTaskExecutor();var future=executor.submit(()->port.embed(request));
        try {
            Result result=future.get(budget,TimeUnit.NANOSECONDS);
            if(!clock.instant().isBefore(request.deadline()))throw new IllegalStateException("EMBEDDING_TIMEOUT");
            if(result==null||!request.model().equals(result.observedModel())||!sha(request.text()).equals(result.inputHash())
                ||result.tokens()<1||result.tokens()>request.model().maxTokens()||!result.truncationDisabled())throw new IllegalArgumentException("EMBEDDING_CONTRACT");
            return vector(request.model(),result.values());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("EMBEDDING_INTERRUPTED");}
        catch(TimeoutException e){throw new IllegalStateException("EMBEDDING_TIMEOUT");}
        catch(ExecutionException e){throw new IllegalStateException("EMBEDDING_FAILED");}
        finally {future.cancel(true);executor.shutdownNow();} // Never wait indefinitely for an uncooperative provider.
    }
    static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("DIGEST_UNAVAILABLE");}}
}
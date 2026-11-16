package com.disasterinfo.eodiisseo.ai.resource.vector;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
public interface RagVectorStore {
    record Claim(UUID token,String state) {}
    record Hit(UUID group,UUID chunk,double distance) {public Hit{if(group==null||chunk==null||!Double.isFinite(distance)||distance<0||distance>2.00001)throw new IllegalArgumentException("INVALID_VECTOR_DISTANCE");}}
    interface Backend {
        boolean available();
        void put(UUID chunk,EmbeddingPort.Model model,double[] vector);
        boolean contains(UUID chunk,EmbeddingPort.Model model);
        List<Hit> search(String hazard,EmbeddingPort.Model model,double[] query,Instant now,int limit);
    }
    boolean available();
    Claim claim(RagChunkGroup group,EmbeddingPort.Model model);
    void commit(RagChunkGroup group,EmbeddingPort.Model model,Claim claim,List<double[]> values,BooleanSupplier approved);
    void fail(RagChunkGroup group,EmbeddingPort.Model model,Claim claim,String code);
    Optional<RagChunkGroup> group(UUID id,EmbeddingPort.Model model);
    List<RagChunkGroup> eligible(String hazard,EmbeddingPort.Model model,Instant now);
    List<Hit> search(String hazard,EmbeddingPort.Model model,double[] query,Instant now,int limit);
}
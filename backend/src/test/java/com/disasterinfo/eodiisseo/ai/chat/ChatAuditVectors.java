package com.disasterinfo.eodiisseo.ai.chat;
import com.disasterinfo.eodiisseo.ai.resource.vector.*;
import org.springframework.jdbc.core.*;
import java.time.Instant;
import java.util.*;
/** Test-only one-hot fixture routing and float8[] scoring. Neither a model nor pgvector. */
final class ChatAuditVectors implements EmbeddingPort,RagVectorStore.Backend {
    static final Model MODEL=new Model("SYNTHETIC-CHAT-9D","sha256:"+"c".repeat(64),9,2048,"SYNTHETIC-CHAT-1");
    private final JdbcTemplate sql;ChatAuditVectors(JdbcTemplate sql){this.sql=sql;}
    public boolean networkFree(){return true;}public boolean available(){return true;}
    public Result embed(Request r){
        int index=-1;var docs=SyntheticRagEvaluation.documents();for(int n=0;n<docs.size();n++)if(docs.get(n).paragraphs().contains(r.text()))index=n;
        if(index<0){String q=r.text();index=q.contains("지진")?(q.contains("엘리베이터")?1:q.contains("실외")?2:0):q.contains("호우")?(q.contains("지하차도")?3:q.contains("지하공간")?4:5):q.contains("연기")?8:q.contains("예방")?7:6;}
        double[] v=new double[9];v[index]=1;return new Result(r.model(),EmbeddingPort.sha(r.text()),4,true,v);
    }
    public void put(UUID id,Model m,double[] vector){
        sql.execute((ConnectionCallback<Void>)c->{var a=c.createArrayOf("float8",Arrays.stream(EmbeddingPort.vector(m,vector)).boxed().toArray());try(var p=c.prepareStatement("insert into rag_test_vector values(?,?,?,?)")){p.setObject(1,id);p.setString(2,m.key());p.setInt(3,m.dimension());p.setArray(4,a);p.executeUpdate();}finally{a.free();}return null;});
    }
    public boolean contains(UUID id,Model m){return sql.queryForObject("select count(*) from rag_test_vector where chunk_id=? and model_key=? and embedding_dimension=?",Long.class,id,m.key(),m.dimension())==1;}
    public List<RagVectorStore.Hit> search(String hazard,Model m,double[] query,Instant now,int limit){
        String base=JdbcRagVectorStore.ELIGIBLE.replace("where g.hazard_type=?","join rag_test_vector v on v.chunk_id=c.chunk_id and v.model_key=c.model_key where g.hazard_type=?");
        return sql.query("select c.group_id,c.chunk_id,v.embedding_values "+base,(rs,n)->{var a=rs.getArray(3);Object[] v=(Object[])a.getArray();double dot=0;for(int i=0;i<v.length;i++)dot+=((Number)v[i]).doubleValue()*query[i];a.free();return new RagVectorStore.Hit((UUID)rs.getObject(1),(UUID)rs.getObject(2),1-dot);},JdbcRagVectorStore.filters(hazard,m,now)).stream().sorted(Comparator.comparingDouble(RagVectorStore.Hit::distance).thenComparing(RagVectorStore.Hit::chunk)).limit(limit).toList();
    }
}
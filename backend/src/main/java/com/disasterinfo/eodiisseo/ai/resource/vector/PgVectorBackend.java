package com.disasterinfo.eodiisseo.ai.resource.vector;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
/** Real pgvector exact cosine SQL. Fail closed when vector is absent; no array/lexical simulation fallback. */
public final class PgVectorBackend implements RagVectorStore.Backend {
    private final JdbcTemplate sql;
    public PgVectorBackend(JdbcTemplate sql){this.sql=sql;}
    public boolean available(){return Boolean.TRUE.equals(sql.queryForObject("select exists(select 1 from pg_extension where extname='vector')",Boolean.class));}
    private void require(){if(!available())throw new IllegalStateException("PGVECTOR_EXTENSION_REQUIRED");}
    private static String literal(EmbeddingPort.Model model,double[] vector){return Arrays.toString(EmbeddingPort.vector(model,vector));}
    public void put(UUID chunk,EmbeddingPort.Model model,double[] vector){
        require();sql.update("insert into rag_vector_embedding(chunk_id,model_key,embedding_dimension,embedding) values(?,?,?,cast(? as vector))",chunk,model.key(),model.dimension(),literal(model,vector));
    }
    public boolean contains(UUID chunk,EmbeddingPort.Model model){
        require();return sql.queryForObject("select count(*) from rag_vector_embedding where chunk_id=? and model_key=? and embedding_dimension=? and vector_dims(embedding)=?",Long.class,chunk,model.key(),model.dimension(),model.dimension())==1;
    }
    public List<RagVectorStore.Hit> search(String hazard,EmbeddingPort.Model model,double[] query,Instant now,int limit){
        require();String base=JdbcRagVectorStore.ELIGIBLE.replace("where g.hazard_type=?","join rag_vector_embedding v on v.chunk_id=c.chunk_id and v.model_key=c.model_key where g.hazard_type=?");
        var args=new ArrayList<Object>();args.add(literal(model,query));args.addAll(Arrays.asList(JdbcRagVectorStore.filters(hazard,model,now)));args.add(model.dimension());args.add(limit);
        return sql.query("select c.group_id,c.chunk_id,(v.embedding <=> cast(? as vector)) as distance "+base+" and v.embedding_dimension=? order by distance,c.chunk_id limit ?",
            (rs,n)->new RagVectorStore.Hit((UUID)rs.getObject(1),(UUID)rs.getObject(2),rs.getDouble(3)),args.toArray());
    }
}
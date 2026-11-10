package com.disasterinfo.eodiisseo.collection.execution;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import tools.jackson.databind.json.JsonMapper;

/** Raw-only portion of the legacy writer contract. Never invokes earthquake(), shelter or other projections. */
public final class JdbcCollectionRawStore {
    private final JdbcTemplate jdbc;
    public record Saved(UUID record,int revision,String hash,String key){}
    public JdbcCollectionRawStore(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    public static Object canonical(Object value){
        if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted;}
        if(value instanceof List<?> list)return list.stream().map(JdbcCollectionRawStore::canonical).toList();return value;
    }
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}}
    public Saved save(UUID source,String key,Map<String,Object> payload,Instant now){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
           ||!org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Raw revisions require the shared final transaction");
        String raw=JsonMapper.builder().build().writeValueAsString(canonical(payload)),hash=hash(raw);
        var old=jdbc.queryForList("select record_id,content_hash from collection_raw_record where source_id=? and source_record_key=? for update",source,key);
        UUID id=old.isEmpty()?UUID.randomUUID():(UUID)old.getFirst().get("record_id");
        if(old.isEmpty())jdbc.update("insert into collection_raw_record values(?,?,?,?::jsonb,?,?,?)",id,source,key,raw,hash,Timestamp.from(now),Timestamp.from(now));
        else jdbc.update("update collection_raw_record set payload=?::jsonb,content_hash=?,collected_at=? where record_id=?",raw,hash,Timestamp.from(now),id);
        if(old.isEmpty()||!hash.equals(old.getFirst().get("content_hash"))){
            int version=jdbc.queryForObject("select coalesce(max(revision_no),0)+1 from collection_raw_revision where record_id=?",Integer.class,id);
            jdbc.update("insert into collection_raw_revision values(?,?,?::jsonb,?,?)",id,version,raw,hash,Timestamp.from(now));
        }
        int version=jdbc.queryForObject("select max(revision_no) from collection_raw_revision where record_id=?",Integer.class,id);
        return new Saved(id,version,hash,key);
    }
}
package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.collection.operations.CollectionAssurance.*;
import com.disasterinfo.eodiisseo.auth.security.MemberPrincipal;
import com.disasterinfo.eodiisseo.auth.exception.ApiProblem;
import com.disasterinfo.eodiisseo.collection.ownership.JdbcCollectionOwnershipStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** Explicit OPS input/revocation service; no automatic approval, file discovery or endpoint. */
public final class JdbcCollectionAssurance {
    private final JdbcTemplate jdbc;private final JdbcCollectionOwnershipStore tx;
    private final java.util.function.Supplier<CollectionOperationsService> ops;private final CollectionOperationMode mode;private final Verifier verifier;
    public JdbcCollectionAssurance(JdbcTemplate jdbc,PlatformTransactionManager manager,CollectionOperationsService ops,CollectionOperationMode mode,Verifier verifier){
        this(jdbc,manager,()->ops,mode,verifier);
    }
    public JdbcCollectionAssurance(JdbcTemplate jdbc,PlatformTransactionManager manager,java.util.function.Supplier<CollectionOperationsService> ops,CollectionOperationMode mode,Verifier verifier){
        this.jdbc=jdbc;this.tx=new JdbcCollectionOwnershipStore(jdbc,manager);this.ops=ops;this.mode=mode;this.verifier=verifier;mode.verify(jdbc.getDataSource());requireSchema(jdbc);
    }
    public static void requireSchema(JdbcTemplate jdbc){if(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2026111006' and success",Long.class)!=1)throw new IllegalStateException("Assurance migration required");}
    public CollectionOperationMode mode(){return mode;}
    public void submit(MemberPrincipal actor,Artifact a){ops.get().withOps(actor,()->{
        if(!verifier.verify(a,mode,now()))throw ApiProblem.conflict("TRUSTED_REVIEW_SIGNATURE_REQUIRED");
        if(jdbc.queryForObject("select count(*) from collection_assurance_artifact where artifact_id=? or (kind=? and scope_key=? and version=?)",Long.class,a.id(),a.kind().name(),a.scope(),a.version())!=0)throw ApiProblem.conflict("ASSURANCE_VERSION_ALREADY_EXISTS");
        jdbc.update("insert into collection_assurance_artifact(artifact_id,kind,scope_key,version,issuer,reference,payload,payload_sha256,review_signature,valid_from,valid_until,synthetic,reviewed_by,session_id) values(?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)",a.id(),a.kind().name(),a.scope(),a.version(),a.issuer(),a.reference(),CollectionAssurance.canonical(a.payload()),a.digest(),a.signature(),Timestamp.from(a.from()),Timestamp.from(a.until()),a.synthetic(),actor.memberId(),actor.sessionId());return null;
    });}
    public void revoke(MemberPrincipal actor,UUID id,String reference){ops.get().withOps(actor,()->{
        CollectionOperationsService.reference(reference);if(jdbc.queryForObject("select count(*) from collection_assurance_artifact where artifact_id=?",Long.class,id)!=1)throw ApiProblem.missing("ASSURANCE_NOT_FOUND");
        if(jdbc.queryForObject("select count(*) from collection_assurance_revocation where artifact_id=?",Long.class,id)!=0)throw ApiProblem.conflict("ASSURANCE_ALREADY_REVOKED");
        jdbc.update("insert into collection_assurance_revocation values(?,?,?,?,clock_timestamp())",id,actor.memberId(),actor.sessionId(),reference);return null;
    });}
    public Optional<Artifact> active(Kind kind,String scope){return stable(view->view.find(kind,scope));}
    /** Recheck and use a snapshot inside one short transaction; never wrap external HTTP in this callback. */
    public <T>T stable(Function<View,T> work){return tx.transaction(ignored->{jdbc.queryForList("select pg_advisory_xact_lock(78161108)");return work.apply(new View());});}
    public final class View {
        public Optional<Artifact> find(Kind kind,String scope){
            var rows=jdbc.queryForList("""
                select a.* from collection_assurance_artifact a where kind=? and scope_key=? and valid_from<=clock_timestamp() and valid_until>clock_timestamp()
                and not exists(select 1 from collection_assurance_revocation r where r.artifact_id=a.artifact_id) order by artifact_id for share of a
                """,kind.name(),scope);
            if(rows.size()!=1)return Optional.empty();var row=rows.getFirst();
            Map<String,Object> payload=JsonMapper.builder().build().readValue(row.get("payload").toString(),Map.class);
            var a=new Artifact((UUID)row.get("artifact_id"),kind,scope,(String)row.get("version"),(String)row.get("issuer"),(String)row.get("reference"),payload,((Timestamp)row.get("valid_from")).toInstant(),((Timestamp)row.get("valid_until")).toInstant(),(boolean)row.get("synthetic"),(byte[])row.get("review_signature"));
            return a.digest().equals(row.get("payload_sha256"))&&verifier.verify(a,mode,now())?Optional.of(a):Optional.empty();
        }
    }
    private Instant now(){return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();}
}
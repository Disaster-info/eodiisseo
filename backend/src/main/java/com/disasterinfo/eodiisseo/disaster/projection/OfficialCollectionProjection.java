package com.disasterinfo.eodiisseo.disaster.projection;

import com.disasterinfo.eodiisseo.collection.execution.*;
import com.disasterinfo.eodiisseo.collection.ownership.*;
import com.disasterinfo.eodiisseo.collection.ownership.CollectionOwnership.Claim;
import com.disasterinfo.eodiisseo.disaster.collection.*;
import com.disasterinfo.eodiisseo.disaster.service.*;
import com.disasterinfo.eodiisseo.disaster.adapter.VerifiedWarningImpactReader;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.collection.service.CollectionRecordWriter;
import com.disasterinfo.eodiisseo.collection.domain.CollectedRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/** Explicit adapter, never a bean by default. Uses the caller's actual JPA/JDBC transaction; no remote I/O. */
public final class OfficialCollectionProjection implements CollectionProjectionPort {
    public static final String RULES="official-domain-projection-v1";
    private final JdbcTemplate jdbc;private final OwnedDatabase db;private final JpaTransactionManager manager;
    private final OfficialRecordNormalizer normalizer;private final OfficialImpactAreaSynchronizer areas;private final VerifiedWarningImpactReader impacts;
    private final CollectionRecordWriter shelters;private final ProjectionAdmissionPort admission;
    private final JdbcCollectionRawStore raw;private final JdbcCollectionOwnershipStore fencing;
    public OfficialCollectionProjection(JdbcTemplate jdbc,OwnedDatabase db,JpaTransactionManager manager,OfficialRecordNormalizer normalizer,
            OfficialImpactAreaSynchronizer areas,VerifiedWarningImpactReader impacts,CollectionRecordWriter shelters,ProjectionAdmissionPort admission){
        this.jdbc=Objects.requireNonNull(jdbc);this.db=Objects.requireNonNull(db);this.manager=Objects.requireNonNull(manager);
        this.fencing=new JdbcCollectionOwnershipStore(jdbc,manager); // Rejects mixed DataSource/EMF managers.
        this.normalizer=Objects.requireNonNull(normalizer);this.areas=Objects.requireNonNull(areas);this.impacts=Objects.requireNonNull(impacts);
        this.shelters=Objects.requireNonNull(shelters);this.admission=Objects.requireNonNull(admission);this.raw=new JdbcCollectionRawStore(jdbc);
    }
    private Instant boundary(Claim claim){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw new IllegalStateException("Final shared transaction required");
        fencing.transaction(tx->{tx.lock(claim.assignment(),claim.mode());tx.assertCurrent(claim,true);return null;});
        var holder=(EntityManagerHolder)TransactionSynchronizationManager.getResource(manager.getEntityManagerFactory());
        if(holder==null)throw new IllegalStateException("Default JPA transaction is not participating");
        int jpa=((Number)holder.getEntityManager().createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
        int sql=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
        if(jpa!=sql||db.count("select pg_backend_pid()")!=sql)throw new IllegalStateException("JPA/JDBC do not share the actual database transaction");
        return jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
    }
    private ProjectionAdmissionPort.Grant grant(Claim c,LocalDate from,LocalDate to,Instant now){
        var g=admission.resolve(c,from,to,now).orElse(null);
        if(g==null||!c.assignment().target().equals(g.target())||!c.assignment().contract().equals(g.contract())||!g.validUntil().isAfter(now))return null;
        if(jdbc.queryForObject("""
            select count(*) from data_source s join collection_source_assignment a on a.source_id=s.source_id and a.epoch=s.collection_assignment_epoch
            where s.source_id=? and s.active and a.approved and a.target_id=? and a.contract_key=?
            """,Long.class,g.canonicalSource(),c.assignment().target(),c.assignment().contract())!=1)return null;
        if(jdbc.queryForObject("select count(*) from collection_projection_receipt where target_id=? and normalizer_version=? and validation_version=? and canonical_source_id<>?",Long.class,g.target(),RULES,g.validationVersion(),g.canonicalSource())>0)
            throw new IllegalStateException("Canonical source policy changed without a new validation version");
        return g;
    }
    public Result projectVerifiedArchive(Claim c){
        Instant now=boundary(c);
        var execution=jdbc.queryForMap("select * from collection_http_execution where run_id=? and projection_required and state='COMPLETE' for update",c.run());
        var from=(java.sql.Date)execution.get("from_date");var to=(java.sql.Date)execution.get("to_date");
        var pages=jdbc.queryForList("""
            select a.attempt_id,a.page_no,b.raw_body,b.body_sha256 from collection_http_attempt a join collection_http_archive b using(attempt_id)
            where a.run_id=? and a.state='VALIDATED' and b.classification='NORMAL' and not b.sensitive_omitted order by a.page_no
            """,c.run());
        if(pages.isEmpty())throw new IllegalStateException("Verified normal archive required");
        var g=grant(c,from.toLocalDate(),to.toLocalDate(),now);
        if(g==null){for(var page:pages)receipt(c,(UUID)page.get("attempt_id"),"__ADMISSION__",null,null,null,"DOMAIN_ADMISSION_REQUIRED",null,null,null,null);return new Result(false,0,"DOMAIN_ADMISSION_REQUIRED");}
        var plugin=new OfficialCollectionProviderPlugin(c.assignment().contract(),(k,r)->{throw new IllegalStateException("Projection never makes HTTP requests");});
        boolean complete=true;String reviewReason="DOMAIN_REVIEW_REQUIRED";int stored=0;var rainEvidence=new HashMap<UUID,VerifiedWarningImpactReader.VerifiedMapping>();
        for(var page:pages){
            byte[] bytes=(byte[])page.get("raw_body");
            if(!HexFormat.of().formatHex(digest(bytes)).equals(page.get("body_sha256")))throw new IllegalStateException("Archive hash mismatch");
            var request=new CollectionProviderPort.Request(from.toLocalDate(),to.toLocalDate(),((Number)page.get("page_no")).intValue(),((Number)execution.get("page_rows")).intValue(),Duration.ofMillis(((Number)execution.get("timeout_ms")).longValue()));
            var decoded=plugin.decode(new CollectionProviderPort.Reply(200,bytes,Duration.ZERO),request);
            if(decoded.items().isEmpty()&&!g.emptyApproved()){receipt(c,(UUID)page.get("attempt_id"),"__EMPTY__",null,null,g,"DOMAIN_EMPTY_UNCONFIRMED",null,null,null,null);complete=false;reviewReason="DOMAIN_EMPTY_UNCONFIRMED";}
            for(var item:decoded.items()){
                String key=rawKey(c.assignment().contract(),item),original=OfficialProviderClient.canonical(item),officialHash=JdbcCollectionRawStore.hash(original);
                JdbcCollectionRawStore.Saved saved;
                UUID sourceRecord=null,disaster=null,revision=null;String mapping=null,result;
                if(c.assignment().contract().equals("MOIS_SHELTERS")){
                    if(jdbc.queryForObject("select count(*) from data_source where source_id=? and source_kind='SHELTER' and endpoint_key='SAFETYDATA_EARTHQUAKE_SHELTER' and active",Long.class,c.assignment().source())!=1)throw new IllegalStateException("Shelter source contract required");
                    shelters.save(c.assignment().source(),"SAFETYDATA_EARTHQUAKE_SHELTER",List.of(new CollectedRecord(key,item)),now);
                    saved=raw.save(c.assignment().source(),key,item,now);result="SHELTER_APPLIED";
                }else{
                    saved=raw.save(c.assignment().source(),key,item,now);
                    var provider=switch(c.assignment().contract()){
                        case "KMA_PORTAL_EQK_MSG","KMA_HUB_EQK_MSG"->OfficialProviderClient.Provider.EARTHQUAKE;
                        case "KMA_RAIN_CODES"->OfficialProviderClient.Provider.HEAVY_RAIN;
                        case "MOIS_MESSAGES"->OfficialProviderClient.Provider.WILDFIRE_MESSAGES;
                        default->throw new IllegalStateException("Domain operation contract required");
                    };
                    var normalized=normalizer.normalize(g.canonicalSource(),provider,item,original,now);result=normalized.classification();sourceRecord=normalized.sourceRecord();
                    if(sourceRecord!=null){
                        var fact=jdbc.queryForMap("select disaster_id from source_disaster where source_disaster_id=?",sourceRecord);disaster=(UUID)fact.get("disaster_id");
                        var revisions=jdbc.queryForList("select revision_id from source_disaster_revision where source_disaster_id=? and content_hash=? order by revision_no desc limit 1",sourceRecord,officialHash);
                        if(revisions.size()!=1)throw new IllegalStateException("Domain revision/hash receipt mismatch");revision=(UUID)revisions.getFirst().get("revision_id");
                        if(provider==OfficialProviderClient.Provider.HEAVY_RAIN){
                            areas.refresh(sourceRecord,now);
                            var proof=impacts.evidence(disaster,now).orElseThrow(()->new IllegalStateException("Complete official impact mapping required"));
                            mapping=proof.mapping().version();rainEvidence.put(disaster,proof);
                        }
                    }else if(!Set.of("OUT_OF_ORDER_INCIDENT","OUT_OF_ORDER_WARNING").contains(result)){complete=false;reviewReason=result;}
                }
                receipt(c,(UUID)page.get("attempt_id"),key,saved,officialHash,g,result,sourceRecord,disaster,revision,mapping);stored++;
            }
        }
        if(!g.equals(grant(c,from.toLocalDate(),to.toLocalDate(),boundary(c))))throw new IllegalStateException("Domain admission changed during projection");
        for(var entry:rainEvidence.entrySet())if(!entry.getValue().equals(impacts.evidence(entry.getKey(),boundary(c)).orElse(null)))throw new IllegalStateException("Impact evidence changed during projection");
        if(!complete)throw new CollectionProjectionPort.ReviewRequired(reviewReason);
        if(g.coverageApproved())coverage(c,g,from.toLocalDate(),to.toLocalDate());
        return new Result(complete,stored,complete?"DOMAIN_APPLIED":"DOMAIN_REVIEW_REQUIRED");
    }
    private static byte[] digest(byte[] bytes){try{return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception impossible){throw new IllegalStateException("Hash unavailable");}}
    private static String value(Map<String,Object> row,String key){String v=Objects.toString(row.get(key),"");if(v.isBlank()||v.length()>100)throw new IllegalStateException("Raw identity required");return v;}
    private static String rawKey(String contract,Map<String,Object> row){
        return switch(contract){
            case "KMA_PORTAL_EQK_MSG","KMA_HUB_EQK_MSG"->value(row,"tmFc").substring(0,6)+":"+value(row,"stnId")+":"+value(row,"tmSeq")+":"+value(row,"fcTp");
            case "KMA_WARNING_LIST"->value(row,"tmFc").substring(0,6)+":"+value(row,"stnId")+":"+value(row,"tmSeq");
            case "KMA_RAIN_CODES"->OfficialRecordNormalizer.identity(OfficialProviderClient.Provider.HEAVY_RAIN,row);
            case "MOIS_MESSAGES"->value(row,"SN");
            case "MOIS_SHELTERS"->value(row,"ARCD")+":"+value(row,"ACMDFCLTY_SN");
            default->throw new IllegalStateException("Unsupported projection contract");
        };
    }
    private void receipt(Claim c,UUID attempt,String key,JdbcCollectionRawStore.Saved raw,String officialHash,ProjectionAdmissionPort.Grant grant,String result,UUID source,UUID disaster,UUID revision,String mapping){
        String safe=result.matches("[A-Z0-9_]{1,80}")?result:"DOMAIN_REVIEW_REQUIRED";
        jdbc.update("""
            insert into collection_projection_receipt(receipt_id,run_id,attempt_id,target_id,canonical_source_id,raw_record_id,raw_revision_no,raw_key,raw_hash,official_hash,
            contract_key,normalizer_version,validation_version,validation_reference,synthetic,result,source_disaster_id,disaster_id,source_revision_id,mapping_version)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),c.run(),attempt,c.assignment().target(),grant==null?null:grant.canonicalSource(),raw==null?null:raw.record(),raw==null?null:raw.revision(),key,raw==null?null:raw.hash(),officialHash,
            c.assignment().contract(),RULES,grant==null?"NOT_ADMITTED":grant.validationVersion(),grant==null?"DOMAIN_ADMISSION_REQUIRED":grant.reference(),grant==null||grant.synthetic(),safe,source,disaster,revision,mapping);
    }
    public void recordRolledBack(Claim c,String reason){
        boundary(c);var execution=jdbc.queryForMap("select * from collection_http_execution where run_id=?",c.run());
        var plugin=new OfficialCollectionProviderPlugin(c.assignment().contract(),(k,r)->{throw new IllegalStateException("No diagnostic HTTP");});
        for(var page:jdbc.queryForList("select a.attempt_id,a.page_no,b.raw_body from collection_http_attempt a join collection_http_archive b using(attempt_id) where a.run_id=? and a.state='VALIDATED'",c.run())){
            var request=new CollectionProviderPort.Request(((java.sql.Date)execution.get("from_date")).toLocalDate(),((java.sql.Date)execution.get("to_date")).toLocalDate(),((Number)page.get("page_no")).intValue(),((Number)execution.get("page_rows")).intValue(),Duration.ofMillis(((Number)execution.get("timeout_ms")).longValue()));
            var rows=plugin.decode(new CollectionProviderPort.Reply(200,(byte[])page.get("raw_body"),Duration.ZERO),request).items();
            if(rows.isEmpty())receipt(c,(UUID)page.get("attempt_id"),"__ROLLED_BACK__",null,null,null,reason,null,null,null,null);
            for(var row:rows)receipt(c,(UUID)page.get("attempt_id"),rawKey(c.assignment().contract(),row),null,JdbcCollectionRawStore.hash(OfficialProviderClient.canonical(row)),null,reason,null,null,null,null);
        }
    }
    private void coverage(Claim c,ProjectionAdmissionPort.Grant g,LocalDate from,LocalDate to){
        jdbc.update("insert into collection_domain_coverage(run_id,target_id,contract_key,mode,from_date,to_date,initial_date,normalizer_version,validation_version,validation_reference,synthetic) values(?,?,?,?,?,?,?,?,?,?,?)",
            c.run(),g.target(),g.contract(),c.mode(),from,to,g.initialDate(),RULES,g.validationVersion(),g.reference(),g.synthetic());
        var previous=jdbc.queryForList("select through_date,initial_date from collection_domain_cursor where target_id=? and mode=? and normalizer_version=? and validation_version=? for update",g.target(),c.mode(),RULES,g.validationVersion());
        if(!previous.isEmpty()&&!g.initialDate().equals(((java.sql.Date)previous.getFirst().get("initial_date")).toLocalDate()))throw new IllegalStateException("Coverage baseline changed");
        LocalDate frontier=previous.isEmpty()?g.initialDate().minusDays(1):((java.sql.Date)previous.getFirst().get("through_date")).toLocalDate();
        for(var row:jdbc.queryForList("select from_date,to_date from collection_domain_coverage where target_id=? and mode=? and normalizer_version=? and validation_version=? and initial_date=? order by from_date,to_date",g.target(),c.mode(),RULES,g.validationVersion(),g.initialDate())){
            LocalDate start=((java.sql.Date)row.get("from_date")).toLocalDate(),end=((java.sql.Date)row.get("to_date")).toLocalDate();
            if(start.isAfter(frontier.plusDays(1)))break;if(end.isAfter(frontier))frontier=end;
        }
        if(!frontier.isBefore(g.initialDate()))jdbc.update("insert into collection_domain_cursor values(?,?,?,?,?,?,?) on conflict(target_id,mode,normalizer_version,validation_version) do update set through_date=excluded.through_date,last_run_id=excluded.last_run_id",
            g.target(),c.mode(),RULES,g.validationVersion(),frontier,g.initialDate(),c.run());
    }
}
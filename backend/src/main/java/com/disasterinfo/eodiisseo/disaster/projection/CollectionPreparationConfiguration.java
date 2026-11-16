package com.disasterinfo.eodiisseo.disaster.projection;
import com.disasterinfo.eodiisseo.collection.operations.*;
import com.disasterinfo.eodiisseo.collection.ownership.*;
import com.disasterinfo.eodiisseo.collection.execution.*;
import com.disasterinfo.eodiisseo.disaster.collection.*;
import com.disasterinfo.eodiisseo.disaster.service.*;
import com.disasterinfo.eodiisseo.disaster.adapter.VerifiedWarningImpactReader;
import com.disasterinfo.eodiisseo.collection.service.CollectionRecordWriter;
import com.disasterinfo.eodiisseo.auth.repository.OwnedDatabase;
import com.disasterinfo.eodiisseo.auth.service.SessionAccessService;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.core.env.Environment;
import java.time.*;
import java.util.*;

/** Optional composition root: Collection core knows only ports. No sender/evidence/grant defaults. */
@Configuration(proxyBeanMethods=false) @Profile("!test")
public class CollectionPreparationConfiguration {
    @Bean @ConditionalOnProperty(name="eodiisseo.collection.operations.admin-enabled",havingValue="true")
    CollectionOperationsService collectionOperationsService(JdbcTemplate jdbc,JpaTransactionManager manager,SessionAccessService sessions,ObjectProvider<CollectionOperationalEvidencePort> evidence){
        CollectionReadiness.requireSchema(jdbc);
        return new CollectionOperationsService(jdbc,manager,sessions,CollectionOperationMode.PRODUCTION,evidence.getIfAvailable());
    }
    @Bean @ConditionalOnProperty(name="eodiisseo.collection.operations.prepare-enabled",havingValue="true")
    PreparedCollectionRuntime preparedCollectionRuntime(JdbcTemplate jdbc,JpaTransactionManager manager,Environment env,
        CollectionOperationalEvidencePort evidence,ProjectionAdmissionPort domain,OwnedDatabase db,
        OfficialRecordNormalizer normalizer,OfficialImpactAreaSynchronizer areas,VerifiedWarningImpactReader impacts,CollectionRecordWriter shelters,
        org.springframework.beans.factory.ListableBeanFactory beans){
        if(beans.getBeanNamesForType(com.disasterinfo.eodiisseo.collection.scheduler.CollectionScheduler.class,false,false).length!=0
            ||beans.getBeanNamesForType(OfficialCollectionScheduler.class,false,false).length!=0)throw new IllegalStateException("Legacy scheduler bean still present");
        var sources=new HashSet<UUID>();for(String value:env.getRequiredProperty("eodiisseo.collection.operations.sources").split(","))sources.add(UUID.fromString(value.trim()));
        var readiness=new CollectionReadiness(jdbc,manager,env,evidence,CollectionOperationMode.PRODUCTION);
        if(!readiness.inspect(sources).prepared())throw new IllegalStateException("Collection preparation denied; inspect operational approvals");
        var sender=Objects.requireNonNull(evidence.transport(),"Reviewed bounded HTTP transport required");
        var plugins=new ArrayList<CollectionProviderPort>();var keys=new HashSet<String>();
        var fencing=new JdbcCollectionOwnershipStore(jdbc,manager);
        for(UUID source:sources){var a=fencing.transaction(tx->tx.assignment(source));if(keys.add(a.contract()))plugins.add(new OfficialCollectionProviderPlugin(a.contract(),sender::exchange,evidence.collectionContract(a.contract())));}
        ProjectionAdmissionPort approved=(claim,from,to,now)->{
            var grant=domain.resolve(claim,from,to,now).orElse(null);if(grant==null||grant.synthetic())return Optional.empty();
            var a=claim.assignment();
            long matches=jdbc.queryForObject("""
                select count(*) from collection_operation_approval p where source_id=? and epoch=? and target_id=? and contract_key=? and quota_scope_id=?
                and canonical_source_id=? and validation_version=? and evidence_reference=? and projection_allowed and coverage_allowed
                and valid_until>? and not synthetic and not exists(select 1 from collection_approval_revocation r where r.approval_id=p.approval_id)
                """,Long.class,a.source(),a.epoch(),a.target(),a.contract(),a.quotaScope(),grant.canonicalSource(),grant.validationVersion(),grant.reference(),java.sql.Timestamp.from(now));
            return matches==1?Optional.of(grant):Optional.empty();
        };
        var projection=new OfficialCollectionProjection(jdbc,db,manager,normalizer,areas,impacts,shelters,approved);
        // Retention comes only from an approved component's bounded explicit deployment setting.
        var archive=Objects.requireNonNull(evidence.archivePolicy(),"Reviewed retention/ACL and secret-redaction policy required");
        var coordinator=new CollectionCoordinator(new CollectionOwnership(fencing,new CollectionContractRegistry(),Duration.ofSeconds(30)),
            new JdbcCollectionExecutionStore(jdbc,manager),plugins,new CollectionCoordinator.Policy(false,10,1,10,Duration.ofSeconds(10),Duration.ZERO),
            archive,projection);
        return new PreparedCollectionRuntime(coordinator,readiness,sources);
    }
}
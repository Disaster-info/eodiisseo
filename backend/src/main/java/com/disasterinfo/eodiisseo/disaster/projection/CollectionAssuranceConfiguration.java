package com.disasterinfo.eodiisseo.disaster.projection;
import com.disasterinfo.eodiisseo.collection.operations.*;
import com.disasterinfo.eodiisseo.disaster.collection.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.time.Clock;
import java.util.*;
/** Preparation only. No production arming, worker or scheduler bean. Missing external bindings fail startup. */
@Configuration(proxyBeanMethods=false) @Profile("!test")
@ConditionalOnProperty(name="eodiisseo.collection.operations.assurance-enabled",havingValue="true")
public class CollectionAssuranceConfiguration {
    @Bean CollectionAssurance.Verifier collectionReviewVerifier(CollectionReviewTrustRootsPort trust){
        var roots=Map.copyOf(trust.roots());if(roots.isEmpty()||roots.values().stream().anyMatch(CollectionAssurance.TrustRoot::synthetic))throw new IllegalStateException("Pinned production review roots required");return new CollectionAssurance.Verifier(roots);
    }
    @Bean JdbcCollectionAssurance collectionAssuranceStore(JdbcTemplate jdbc,JpaTransactionManager manager,ObjectProvider<CollectionOperationsService> ops,CollectionAssurance.Verifier verifier){return new JdbcCollectionAssurance(jdbc,manager,ops::getObject,CollectionOperationMode.PRODUCTION,verifier);}
    @Bean VerifiedCollectionOperationalEvidence verifiedCollectionEvidence(JdbcCollectionAssurance store,CollectionFleetEvidencePort fleet,CollectionAssuranceBindingsPort bindings){
        var sender=Objects.requireNonNull(bindings.sender());if(sender.synthetic())throw new IllegalStateException("Synthetic sender is audit-only");var privacy=Objects.requireNonNull(bindings.privacy(),"Reviewed privacy profile required");
        var transport=new OfficialCollectionHttpTransport(bindings.decodedCredentials(),sender);
        var binding=new VerifiedCollectionOperationalEvidence.SenderBinding(transport::exchange,sender.implementationFingerprint(),sender.runtimeFingerprint(),ReviewedOfficialHttpSender.endpointFingerprint(),false);
        return new VerifiedCollectionOperationalEvidence(store,fleet,binding,bindings.knownSecretRepresentations(),Clock.systemUTC(),privacy);
    }
    @Bean ProjectionAdmissionPort reviewedProjectionAdmission(JdbcCollectionAssurance store){
        try(var bytes=OfficialRecordNormalizer.class.getResourceAsStream("OfficialRecordNormalizer.class")){
            var digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(bytes).readAllBytes()));return new ReviewedProjectionAdmission(store,digest);
        }catch(Exception invalid){throw new IllegalStateException("Normalizer build fingerprint required");}
    }
}
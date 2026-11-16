package com.disasterinfo.eodiisseo.collection.operations;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.util.*;

/** Explicit isolated composition root only. No credential discovery, arming API or scheduler.
 * Deploy Worker/Gateway/OPS as separate processes, not parent/child contexts sharing secrets.
 * Supply exactly one private LOGIN DataSource, its EMF/TM and reviewed evidence bindings.
 * Intentionally no @Primary or auto-created pool; ambiguous/missing bindings fail startup. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="eodiisseo.collection.login-plane.enabled",havingValue="true")
public class CollectionLoginPlaneConfiguration {
    @Bean SmartInitializingSingleton collectionLoginPlaneStartup(CollectionLoginPlane plane,JdbcTemplate jdbc,
            JpaTransactionManager manager,CollectionReviewTrustRootsPort trust,CollectionLoginReviewPort reviews,
            org.springframework.beans.factory.ListableBeanFactory beans,org.springframework.core.env.Environment environment) {
        return ()->{
            if(beans instanceof org.springframework.beans.factory.HierarchicalBeanFactory hierarchy && hierarchy.getParentBeanFactory()!=null)
                throw new IllegalStateException("Credential planes must be independent root contexts");
            if(!plane.kind().name().equals(environment.getProperty("eodiisseo.collection.login-plane.kind")))
                throw new IllegalStateException("Exact configured plane required");
            var roots=Map.copyOf(trust.roots());
            if(roots.isEmpty() || roots.values().stream().anyMatch(CollectionAssurance.TrustRoot::synthetic))
                throw new IllegalStateException("Pinned non-synthetic review roots required");
            if(beans.getBeansOfType(javax.sql.DataSource.class).size()!=1 || beans.getBeansOfType(JpaTransactionManager.class).size()!=1 ||
               beans.getBeansOfType(jakarta.persistence.EntityManagerFactory.class).size()!=1 || beans.getBeansOfType(JdbcTemplate.class).size()!=1 ||
               beans.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class).size()!=1)
                throw new IllegalStateException("One credential plane per isolated composition root required");
            plane.verify(jdbc,manager);
            review(plane,jdbc,manager,trust,reviews).run();
            if(plane.kind()!=CollectionLoginPlane.Kind.OPS &&
               (!beans.getBeansOfType(ProductionCollectionCutover.class).isEmpty() || !beans.getBeansOfType(CollectionOperationsService.class).isEmpty() ||
                !beans.getBeansOfType(JdbcCollectionAssurance.class).isEmpty() || !beans.getBeansOfType(VerifiedCollectionOperationalEvidence.class).isEmpty()))
                throw new IllegalStateException("Worker/Gateway must not contain OPS credential plane");
            if(plane.kind()!=CollectionLoginPlane.Kind.GATEWAY && !beans.getBeansOfType(ProductionCollectionWriteGateway.class).isEmpty())
                throw new IllegalStateException("Gateway credential plane must be isolated");
        };
    }
    @Bean
    @ConditionalOnProperty(name="eodiisseo.collection.login-plane.kind",havingValue="GATEWAY")
    ProductionCollectionWriteGateway collectionProductionWrites(CollectionLoginPlane plane,JdbcTemplate jdbc,JpaTransactionManager manager,CollectionReviewTrustRootsPort trust,CollectionLoginReviewPort reviews){
        if(plane.kind()!=CollectionLoginPlane.Kind.GATEWAY)throw new IllegalStateException("Gateway plane binding required");
        return new ProductionCollectionWriteGateway(jdbc,manager,review(plane,jdbc,manager,trust,reviews));
    }
    @Bean
    @ConditionalOnProperty(name="eodiisseo.collection.login-plane.kind",havingValue="OPS")
    CollectionOperationsService collectionProductionOperations(CollectionLoginPlane plane,JdbcTemplate jdbc,JpaTransactionManager manager,VerifiedCollectionOperationalEvidence evidence,CollectionReviewTrustRootsPort trust,CollectionLoginReviewPort reviews){
        return CollectionOperationsService.production(jdbc,manager,evidence,review(plane,jdbc,manager,trust,reviews));
    }
    @Bean
    @ConditionalOnProperty(name="eodiisseo.collection.login-plane.kind",havingValue="OPS")
    ProductionCollectionCutover collectionProductionCutover(CollectionLoginPlane plane,JdbcTemplate jdbc,CollectionOperationsService ops,
            JdbcCollectionAssurance assurance,VerifiedCollectionOperationalEvidence evidence){
        if(plane.kind()!=CollectionLoginPlane.Kind.OPS)throw new IllegalStateException("OPS plane binding required");
        return new ProductionCollectionCutover(jdbc,ops,assurance,evidence);
    }
    private static Runnable review(CollectionLoginPlane plane,JdbcTemplate jdbc,JpaTransactionManager manager,
            CollectionReviewTrustRootsPort trust,CollectionLoginReviewPort reviews){
        return ()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(s->{
            plane.verifyReview(jdbc,new CollectionAssurance.Verifier(trust.roots()),reviews);return null;
        });
    }
}

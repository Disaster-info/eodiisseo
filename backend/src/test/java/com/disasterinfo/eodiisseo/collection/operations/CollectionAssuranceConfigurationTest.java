package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.disaster.projection.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CollectionAssuranceConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(CollectionAssuranceConfiguration.class,CollectionPreparationConfiguration.class);
    @Test void defaultRegistersNoAuthoritySenderGrantOrWorker(){runner.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(JdbcCollectionAssurance.class).isEmpty());assertTrue(c.getBeansOfType(CollectionOperationalEvidencePort.class).isEmpty());assertTrue(c.getBeansOfType(ProjectionAdmissionPort.class).isEmpty());assertTrue(c.getBeansOfType(PreparedCollectionRuntime.class).isEmpty());});}
    @Test void assuranceFlagAloneCannotCreateTrustOrCredentials(){runner.withPropertyValues("eodiisseo.collection.operations.assurance-enabled=true").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void testRootsCannotBeRegisteredAsProductionTrust()throws Exception{var key=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();runner.withPropertyValues("eodiisseo.collection.operations.assurance-enabled=true").withBean(CollectionReviewTrustRootsPort.class,()->()->java.util.Map.of("SYNTHETIC",new CollectionAssurance.TrustRoot(key,java.util.Set.of(CollectionAssurance.Kind.FLEET),true))).run(c->assertNotNull(c.getStartupFailure()));}
}
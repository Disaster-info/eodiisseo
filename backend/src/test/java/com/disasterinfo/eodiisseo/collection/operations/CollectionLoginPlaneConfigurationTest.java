package com.disasterinfo.eodiisseo.collection.operations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
class CollectionLoginPlaneConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(CollectionLoginPlaneConfiguration.class);
    @Test void defaultHasNoPoolGatewayCutoverOrWorker(){runner.run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(javax.sql.DataSource.class).isEmpty());assertTrue(c.getBeansOfType(ProductionCollectionWriteGateway.class).isEmpty());assertTrue(c.getBeansOfType(ProductionCollectionCutover.class).isEmpty());assertTrue(c.getBeansOfType(PreparedCollectionRuntime.class).isEmpty());});}
    @Test void booleanAloneCannotSupplyLoginOrAuthority(){runner.withPropertyValues("eodiisseo.collection.login-plane.enabled=true").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void selectingGatewayWithoutPoolAndTrustFailsStartup(){runner.withPropertyValues("eodiisseo.collection.login-plane.enabled=true","eodiisseo.collection.login-plane.kind=GATEWAY").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void credentialUrlsAndShortSecretsAreRejectedBeforeNetwork(){assertThrows(IllegalArgumentException.class,()->new CollectionLoginPool("jdbc:postgresql://127.0.0.1/db?password=secret","test",new char[32],"test",1));assertThrows(IllegalArgumentException.class,()->new CollectionLoginPool("jdbc:postgresql://127.0.0.1/db","test",new char[1],"test",1));}
}

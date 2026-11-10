package com.disasterinfo.eodiisseo.collection.operations;
import com.disasterinfo.eodiisseo.disaster.projection.CollectionPreparationConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CollectionPreparationConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(CollectionPreparationConfiguration.class,CollectionOperationsController.class);
    @Test void defaultHasNoOperationsBeansOrExecutionEntry(){runner.run(context->{assertNull(context.getStartupFailure());assertEquals(0,context.getBeansOfType(PreparedCollectionRuntime.class).size());assertEquals(0,context.getBeansOfType(CollectionOperationsService.class).size());assertEquals(0,context.getBeansOfType(CollectionOperationsController.class).size());assertEquals(0,context.getBeansOfType(com.disasterinfo.eodiisseo.collection.execution.CollectionCoordinator.class).size());});}
    @Test void prepareFlagAloneCannotStartRuntime(){runner.withPropertyValues("eodiisseo.collection.operations.prepare-enabled=true").run(context->assertNotNull(context.getStartupFailure()));}
    @Test void adminFlagRequiresSchemaAndExistingAuthenticationServices(){runner.withPropertyValues("eodiisseo.collection.operations.admin-enabled=true").run(context->assertNotNull(context.getStartupFailure()));}
    @Test void sensitiveOrUrlEvidenceReferencesAreRejected(){assertThrows(com.disasterinfo.eodiisseo.auth.exception.ApiProblem.class,()->CollectionOperationsService.reference("https://host/?token=secret"));assertThrows(com.disasterinfo.eodiisseo.auth.exception.ApiProblem.class,()->CollectionOperationsService.reference("raw secret value"));assertEquals("OPS:REVIEW-20261110",CollectionOperationsService.reference("OPS:REVIEW-20261110"));}
}
package com.disasterinfo.eodiisseo.ai.summary;

import com.disasterinfo.eodiisseo.ai.summary.adapter.JpaSummaryStore;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryGenerator;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryTaskDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummarySpringContextTest {
    @Test
    void missingAuthLlmDatasourceAndDispatcherNeverPreventSpringStartupOrBypassAuth() {
        try (var context = new AnnotationConfigApplicationContext(SummaryService.class, JpaSummaryStore.class)) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBeansOfType(SummaryGenerator.class)).isEmpty();
            assertThat(context.getBeansOfType(SummaryTaskDispatcher.class)).isEmpty();
            var service = context.getBean(SummaryService.class);
            assertThatThrownBy(() -> service.getSummary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(SummaryException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(SummaryException.Reason.DEPENDENCY_UNAVAILABLE));
            assertThat(service.onOfficialRevisionSaved(UUID.randomUUID(), Instant.now()))
                    .isEqualTo(SummaryService.Submission.DEPENDENCY_UNAVAILABLE);
            assertThat(service.processRevision(UUID.randomUUID(), Instant.now()))
                    .isEqualTo(SummaryService.Processing.DEPENDENCY_UNAVAILABLE);
        }
    }
}

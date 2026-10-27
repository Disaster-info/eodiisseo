package com.disasterinfo.eodiisseo.ai.resource;

import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort;
import com.disasterinfo.eodiisseo.ai.resource.adapter.JdbcOfficialResourceStore;
import com.disasterinfo.eodiisseo.ai.resource.adapter.KoreanLexicalParagraphSearch;
import com.disasterinfo.eodiisseo.ai.resource.port.ResourceApprovalPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceSpringContextTest {
    @Test void realResourceAdapterIsWiredButNeverInventsDatabaseApprovalOrEvidence() {
        try (var context = new AnnotationConfigApplicationContext(ResourceService.class, JdbcOfficialResourceStore.class, KoreanLexicalParagraphSearch.class)) {
            assertThat(context.getBeansOfType(ChatOfficialResourceSearchPort.class)).hasSize(1);
            assertThat(context.getBeansOfType(ResourceApprovalPort.class)).isEmpty();
            assertThat(context.getBean(JdbcOfficialResourceStore.class).isAvailable()).isFalse();
            assertThat(context.getBean(ResourceService.class).search("지진 대처 방법").resources()).isEmpty();
            assertThatThrownBy(() -> context.getBean(JdbcOfficialResourceStore.class).findById(java.util.UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}

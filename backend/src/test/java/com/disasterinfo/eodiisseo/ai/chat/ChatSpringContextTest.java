package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.ai.chat.adapter.JpaChatAnswerStore;
import com.disasterinfo.eodiisseo.ai.chat.dto.ChatRequest;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAuthenticationPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatOfficialResourceSearchPort;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatPromptPrivacyPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatSpringContextTest {

    @Test
    void missingAuthenticationSearchLlmAndDatabaseDoNotPreventSpringStartup() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ChatService.class, JpaChatAnswerStore.class)) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBeansOfType(ChatAuthenticationPort.class)).isEmpty();
            assertThat(context.getBeansOfType(ChatOfficialResourceSearchPort.class)).isEmpty();
            assertThat(context.getBeansOfType(ChatAnswerGenerationPort.class)).isEmpty();
            assertThat(context.getBeansOfType(ChatPromptPrivacyPort.class)).isEmpty();
            assertThat(context.getBean(JpaChatAnswerStore.class).isAvailable()).isFalse();
            assertThatThrownBy(() -> context.getBean(ChatService.class).requestGuidance(UUID.randomUUID(), UUID.randomUUID(), new ChatRequest("질문")))
                    .isInstanceOfSatisfying(ChatException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(ChatException.Reason.DEPENDENCY_UNAVAILABLE));
        }
    }
}

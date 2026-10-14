package com.disasterinfo.eodiisseo.ai.ollama;

import com.disasterinfo.eodiisseo.ai.chat.adapter.OllamaChatAnswerGenerator;
import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort;
import com.disasterinfo.eodiisseo.ai.summary.adapter.OllamaSummaryGenerator;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Deferred import only, deliberately not a component or a nested configuration candidate.
 * Existing generation implementations are evaluated before either fallback Port is registered.
 */
@ConditionalOnProperty(name = OllamaSettings.ENABLED, havingValue = "true", matchIfMissing = false)
public class OllamaAdapterBeans {
    @Bean @ConditionalOnMissingBean(SummaryGenerator.class)
    public SummaryGenerator ollamaSummaryGenerator(OllamaChatClient client) { return new OllamaSummaryGenerator(client); }
    @Bean @ConditionalOnMissingBean(ChatAnswerGenerationPort.class)
    public ChatAnswerGenerationPort ollamaChatAnswerGenerator(OllamaChatClient client) { return new OllamaChatAnswerGenerator(client); }
}

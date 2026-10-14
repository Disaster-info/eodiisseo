package com.disasterinfo.eodiisseo.ai.ollama;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

/** Only explicit activation registers the real transport and both existing generation Ports. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = OllamaSettings.ENABLED, havingValue = "true", matchIfMissing = false)
@Import(OllamaConfiguration.AdapterSelector.class)
public class OllamaConfiguration {
    @Bean public OllamaSettings ollamaSettings(Environment environment) { return OllamaSettings.from(environment); }
    @Bean(destroyMethod = "close") public OllamaChatClient ollamaChatClient(OllamaSettings settings) { return new OllamaChatClient(settings); }

    /** Evaluate fallback Ports after all regular configurations, independent of registration order. */
    public static class AdapterSelector implements DeferredImportSelector {
        @Override public String[] selectImports(AnnotationMetadata metadata) {
            return new String[]{OllamaAdapterBeans.class.getName()};
        }
    }

}

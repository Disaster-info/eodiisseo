package com.disasterinfo.eodiisseo.ai.ollama;

import com.disasterinfo.eodiisseo.ai.chat.port.ChatAnswerGenerationPort;
import com.disasterinfo.eodiisseo.ai.summary.OfficialRevision;
import com.disasterinfo.eodiisseo.ai.summary.port.SummaryGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.disasterinfo.eodiisseo.ai.ollama.OllamaException.Reason.*;
import static org.assertj.core.api.Assertions.*;

/** Exact OS environment names and fail-closed connection policy, independent of application.yml. */
class OllamaConfigurationTest {
    @Test void bindsTheFourRequestedOsEnvironmentNamesAndDoesNotCallTheServerDuringBeanCreation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(500,-1); exchange.close(); });
        server.start();
        Map<String,Object> variables = validVariables();
        variables.put("EODIISSEO_AI_OLLAMA_BASE_URL","http://127.0.0.1:"+server.getAddress().getPort());
        try (var context = context(variables)) {
            assertThat(context.isActive()).isTrue();
            var settings = context.getBean(OllamaSettings.class);
            assertThat(settings.baseUrl()).isEqualTo(URI.create((String)variables.get("EODIISSEO_AI_OLLAMA_BASE_URL")));
            assertThat(settings.model()).isEqualTo("gemma4:e2b-it-q4_K_M");
            assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
            assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(context.getBeansOfType(OllamaChatClient.class)).hasSize(1);
            assertThat(context.getBeansOfType(SummaryGenerator.class)).hasSize(1);
            assertThat(context.getBeansOfType(ChatAnswerGenerationPort.class)).hasSize(1);
            assertThat(context.getBean(OllamaChatClient.class).isConfigured()).isTrue();
            assertThat(calls).hasValue(0);
        } finally { server.stop(0); }
    }
    @Test void omittedActivationKeepsTheApplicationContextWorkingWithoutAnyAiBeans() {
        try (var context = context(Map.of())) { assertDisabled(context); }
    }
    @ParameterizedTest @ValueSource(strings={"false","FALSE","0","yes",""})
    void onlyExplicitTrueActivationRegistersTheRealAdapters(String enabled) {
        Map<String,Object> variables = new HashMap<>(); variables.put(OllamaSettings.ENABLED,enabled);
        variables.put("EODIISSEO_AI_OLLAMA_BASE_URL","not a valid connection setting");
        variables.put("EODIISSEO_AI_OLLAMA_MODEL","invalid model input");
        variables.put("EODIISSEO_AI_OLLAMA_REQUEST_TIMEOUT","invalid time input");
        try (var context = context(variables)) { assertDisabled(context); }
    }
    @Test void optionalConnectTimeoutUsesItsOwnExactEnvironmentName() {
        var variables = validVariables(); variables.put("EODIISSEO_AI_OLLAMA_CONNECT_TIMEOUT","PT0.125S");
        assertThat(OllamaSettings.from(environment(variables)).connectTimeout()).isEqualTo(Duration.ofMillis(125));
    }
    @Test void modelIsMandatoryWhenActivationIsRequestedAndHasNoInventedProductionDefault() {
        var variables = validVariables(); variables.remove("EODIISSEO_AI_OLLAMA_MODEL");
        assertFailure(() -> OllamaSettings.from(environment(variables)),INVALID_MODEL);
    }
    @Test void requestTimeoutIsMandatoryWhenActivationIsRequested() {
        var variables = validVariables(); variables.remove("EODIISSEO_AI_OLLAMA_REQUEST_TIMEOUT");
        assertFailure(() -> OllamaSettings.from(environment(variables)),INVALID_TIMEOUT);
    }
    @Test void localBaseUrlDefaultIsUsedOnlyAsAConnectionSetting() {
        var variables = validVariables(); variables.remove("EODIISSEO_AI_OLLAMA_BASE_URL");
        var settings = OllamaSettings.from(environment(variables));
        assertThat(settings.baseUrl()).isEqualTo(URI.create("http://127.0.0.1:11434"));
        assertThat(settings.allowedHosts()).containsExactlyInAnyOrder("127.0.0.1","localhost","::1");
        assertThat(settings.toString()).doesNotContain("127.0.0.1","gemma4");
    }
    @ParameterizedTest @ValueSource(strings={
            "http://example.com:11434", "http://127.0.0.1.evil.invalid:11434",
            "http://test-user:test-sensitive-password@127.0.0.1:11434",
            "http://127.0.0.1:11434/path", "http://127.0.0.1:11434/?token=test-sensitive-password",
            "http://127.0.0.1:11434/#fragment", "file:///tmp/model", "ftp://127.0.0.1:11434",
            "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://2130706433:11434"
    }) void rejectsUnsafeBaseUrls(String url) {
        assertFailure(() -> settings(URI.create(url),Set.of("127.0.0.1","localhost","::1")),INVALID_BASE_URL);
    }
    @Test void optionalPrivateGpuHostRequiresLiteralIpExplicitAllowlistAndHttps() {
        assertFailure(() -> settings(URI.create("https://10.2.3.4:11434"),Set.of("127.0.0.1")),INVALID_BASE_URL);
        assertFailure(() -> settings(URI.create("http://10.2.3.4:11434"),Set.of("10.2.3.4")),INVALID_BASE_URL);
        assertThat(settings(URI.create("https://10.2.3.4:11434"),Set.of("10.2.3.4")).baseUrl())
                .isEqualTo(URI.create("https://10.2.3.4:11434"));
        assertFailure(() -> settings(URI.create("https://gpu.example.invalid:11434"),Set.of("gpu.example.invalid")),INVALID_BASE_URL);
        assertFailure(() -> settings(URI.create("https://8.8.8.8:11434"),Set.of("8.8.8.8")),INVALID_BASE_URL);
    }
    @Test void exactLocalIpv6LiteralIsRecognizedWithoutArbitraryDnsLookups() {
        assertThat(settings(URI.create("http://[::1]:11434"),Set.of("::1")).baseUrl())
                .isEqualTo(URI.create("http://[::1]:11434"));
    }
    @ParameterizedTest @ValueSource(strings={""," ","\u00a0","test model","test\nmodel","../test:model","test:model:extra"})
    void rejectsInvalidOrWhitespaceOnlyModelNames(String model) {
        assertFailure(() -> new OllamaSettings(URI.create("http://127.0.0.1:11434"),model,Duration.ofSeconds(1),Duration.ofSeconds(1),Set.of("127.0.0.1")),INVALID_MODEL);
    }
    @Test void rejectsInvalidTimeoutsAndOverflowWithoutExposingConfigurationValues() {
        for (Duration duration : new Duration[]{Duration.ZERO,Duration.ofMillis(-1),Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertFailure(() -> new OllamaSettings(URI.create("http://127.0.0.1:11434"),"test-model",duration,Duration.ofSeconds(1),Set.of("127.0.0.1")),INVALID_TIMEOUT);
            assertFailure(() -> new OllamaSettings(URI.create("http://127.0.0.1:11434"),"test-model",Duration.ofSeconds(1),duration,Set.of("127.0.0.1")),INVALID_TIMEOUT);
        }
        var variables = validVariables(); variables.put("EODIISSEO_AI_OLLAMA_REQUEST_TIMEOUT","test-sensitive-timeout");
        assertFailure(() -> OllamaSettings.from(environment(variables)),INVALID_TIMEOUT);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void preservesAnExistingPortImplementationInsteadOfRegisteringDuplicateGenerationBeans(boolean registerOllamaFirst) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment(validVariables()));
            if (registerOllamaFirst) context.register(OllamaConfiguration.class,ExistingPorts.class);
            else context.register(ExistingPorts.class,OllamaConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(SummaryGenerator.class)).hasSize(1);
            assertThat(context.getBeansOfType(ChatAnswerGenerationPort.class)).hasSize(1);
            assertThat(context.getBean(SummaryGenerator.class)).isSameAs(context.getBean("testExistingSummary"));
            assertThat(context.getBean(ChatAnswerGenerationPort.class)).isSameAs(context.getBean("testExistingChat"));
        }
    }

    private static OllamaSettings settings(URI uri,Set<String> hosts) {
        return new OllamaSettings(uri,"test-model",Duration.ofSeconds(1),Duration.ofSeconds(1),hosts);
    }
    private static Map<String,Object> validVariables() {
        var values = new HashMap<String,Object>(); values.put(OllamaSettings.ENABLED,"true");
        values.put("EODIISSEO_AI_OLLAMA_BASE_URL","http://127.0.0.1:11434");
        values.put("EODIISSEO_AI_OLLAMA_MODEL","gemma4:e2b-it-q4_K_M");
        values.put("EODIISSEO_AI_OLLAMA_REQUEST_TIMEOUT","PT7S"); return values;
    }
    private static StandardEnvironment environment(Map<String,Object> variables) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",variables));
        return environment;
    }
    private static AnnotationConfigApplicationContext context(Map<String,Object> variables) {
        var context = new AnnotationConfigApplicationContext(); context.setEnvironment(environment(variables));
        context.register(OllamaConfiguration.class); context.refresh(); return context;
    }
    private static void assertDisabled(AnnotationConfigApplicationContext context) {
        assertThat(context.isActive()).isTrue(); assertThat(context.getBeansOfType(OllamaSettings.class)).isEmpty();
        assertThat(context.getBeansOfType(OllamaChatClient.class)).isEmpty();
        assertThat(context.getBeansOfType(SummaryGenerator.class)).isEmpty();
        assertThat(context.getBeansOfType(ChatAnswerGenerationPort.class)).isEmpty();
    }
    private static void assertFailure(Runnable action,OllamaException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OllamaException.class,failure -> {
            assertThat(failure.reason()).isEqualTo(reason); assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("test-sensitive-password","test-sensitive-timeout","127.0.0.1");
        });
    }
    @TestConfiguration(proxyBeanMethods = false) static class ExistingPorts {
        @Bean SummaryGenerator testExistingSummary() {
            return new SummaryGenerator() {
                @Override public boolean isConfigured() { return false; }
                @Override public Generated generate(OfficialRevision revision) { throw new UnsupportedOperationException("Test-only existing port."); }
            };
        }
        @Bean ChatAnswerGenerationPort testExistingChat() {
            return new ChatAnswerGenerationPort() {
                @Override public boolean isConfigured() { return false; }
                @Override public GeneratedAnswer generate(GenerationRequest request) { throw new UnsupportedOperationException("Test-only existing port."); }
            };
        }
    }
}

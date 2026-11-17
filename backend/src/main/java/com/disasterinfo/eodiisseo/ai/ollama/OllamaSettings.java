package com.disasterinfo.eodiisseo.ai.ollama;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static com.disasterinfo.eodiisseo.ai.ollama.OllamaException.Reason.*;

/** Explicit AI-only environment contract; no global application or authentication configuration. */
public record OllamaSettings(URI baseUrl, String model, Duration requestTimeout,
                             Duration connectTimeout, Set<String> allowedHosts) {
    public static final String ENABLED = "EODIISSEO_AI_OLLAMA_ENABLED";
    private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "::1");

    public OllamaSettings {
        if (baseUrl == null || allowedHosts == null) throw new OllamaException(INVALID_BASE_URL);
        allowedHosts = allowedHosts.stream().map(OllamaSettings::host).collect(Collectors.toUnmodifiableSet());
        if (allowedHosts.isEmpty() || allowedHosts.stream().anyMatch(value ->
                !LOCAL_HOSTS.contains(value) && !privateIpv4(value))) throw new OllamaException(INVALID_BASE_URL);
        String target = host(baseUrl.getHost());
        String scheme = baseUrl.getScheme();
        if (target.isEmpty() || !allowedHosts.contains(target)
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || (!LOCAL_HOSTS.contains(target) && !"https".equalsIgnoreCase(scheme))
                || baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || !(baseUrl.getRawPath() == null || baseUrl.getRawPath().isEmpty() || baseUrl.getRawPath().equals("/"))
                || baseUrl.getPort() == 0 || baseUrl.getPort() > 65535) throw new OllamaException(INVALID_BASE_URL);
        if (model == null || model.length() > 100
                || !model.matches("[A-Za-z0-9][A-Za-z0-9._/-]*(?::[A-Za-z0-9][A-Za-z0-9._-]*)?"))
            throw new OllamaException(INVALID_MODEL);
        validateDuration(requestTimeout);
        validateDuration(connectTimeout);
    }

    public static OllamaSettings from(Environment environment) {
        String url = environment.getProperty("EODIISSEO_AI_OLLAMA_BASE_URL", "http://127.0.0.1:11434");
        URI base;
        try { base = URI.create(url); }
        catch (RuntimeException failure) { throw new OllamaException(INVALID_BASE_URL); }
        String hosts = environment.getProperty("EODIISSEO_AI_OLLAMA_ALLOWED_HOSTS", "127.0.0.1,localhost,::1");
        Set<String> allowed = Arrays.stream(hosts.split(",", -1)).map(String::strip).collect(Collectors.toSet());
        return new OllamaSettings(base, environment.getProperty("EODIISSEO_AI_OLLAMA_MODEL"),
                duration(environment.getProperty("EODIISSEO_AI_OLLAMA_REQUEST_TIMEOUT")),
                duration(environment.getProperty("EODIISSEO_AI_OLLAMA_CONNECT_TIMEOUT", "PT5S")), allowed);
    }

    private static Duration duration(String value) {
        try { return Duration.parse(value); }
        catch (RuntimeException failure) { throw new OllamaException(INVALID_TIMEOUT); }
    }

    private static void validateDuration(Duration value) {
        try {
            if (value == null || value.isZero() || value.isNegative() || value.toNanos() <= 0)
                throw new OllamaException(INVALID_TIMEOUT);
        } catch (ArithmeticException failure) { throw new OllamaException(INVALID_TIMEOUT); }
    }

    private static String host(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("[") && normalized.endsWith("]")
                ? normalized.substring(1, normalized.length() - 1) : normalized;
    }

    /** Private literal IPs require both an explicit allowlist and HTTPS; arbitrary DNS is never accepted. */
    private static boolean privateIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        int[] parsed = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!octets[i].matches("0|[1-9][0-9]{0,2}")) return false;
            parsed[i] = Integer.parseInt(octets[i]);
            if (parsed[i] > 255) return false;
        }
        return parsed[0] == 10 || (parsed[0] == 172 && parsed[1] >= 16 && parsed[1] <= 31)
                || (parsed[0] == 192 && parsed[1] == 168);
    }

    @Override public String toString() { return "OllamaSettings[connection settings redacted]"; }
}

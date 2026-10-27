package com.disasterinfo.eodiisseo.ai.ollama;

import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.disasterinfo.eodiisseo.ai.ollama.OllamaException.Reason.*;

/** Shared real JDK HTTP transport. No automatic requests, redirects, proxies, credentials or logging. */
public class OllamaChatClient implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 65_536;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final OllamaSettings settings;
    private final HttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();

    public OllamaChatClient(OllamaSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings);
        http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) {}
                }).build();
    }

    public boolean isConfigured() { return !closed.get() && !http.isTerminated(); }
    public String modelName() { return settings.model(); }

    public record Response(String content, String modelName, Duration elapsed) {
        @Override public String toString() { return "OllamaResponse[content=<redacted>, model=" + modelName + ", elapsed=" + elapsed + "]"; }
    }

    /** A supplied deadline caps both response headers and the entire body, including slow body reads. */
    public Response chat(String systemPrompt, String userPrompt, Map<String, Object> jsonSchema, Instant deadline) {
        if (Thread.currentThread().isInterrupted()) throw new OllamaException(INTERRUPTED);
        if (!isConfigured() || !hasText(systemPrompt) || !hasText(userPrompt) || jsonSchema == null || jsonSchema.isEmpty())
            throw new OllamaException(INVALID_INPUT);
        Duration budget = settings.requestTimeout();
        if (deadline != null) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) throw new OllamaException(REQUEST_TIMEOUT);
            if (remaining.compareTo(budget) < 0) budget = remaining;
        }
        byte[] body = OllamaJson.write(Map.of("model", settings.model(), "stream", false,
                "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)), "format", jsonSchema,
                "options", Map.of("temperature", 0.2, "num_ctx", 4096))).getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_REQUEST_BYTES) throw new OllamaException(CONTEXT_LIMIT_EXCEEDED);
        if (deadline != null) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) throw new OllamaException(REQUEST_TIMEOUT);
            if (remaining.compareTo(budget) < 0) budget = remaining;
        }
        HttpRequest request = HttpRequest.newBuilder(settings.baseUrl().resolve("/api/chat"))
                .timeout(budget).header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> exchange;
        try { exchange = http.sendAsync(request, info -> new BoundedBody()); }
        catch (RuntimeException failure) { throw new OllamaException(CONNECTION_FAILED); }
        try {
            long remainingNanos = budget.toNanos() - (System.nanoTime() - started);
            if (remainingNanos <= 0) throw new OllamaException(REQUEST_TIMEOUT);
            HttpResponse<byte[]> response = exchange.get(remainingNanos, TimeUnit.NANOSECONDS);
            if (deadline != null && !Instant.now().isBefore(deadline)) throw new OllamaException(REQUEST_TIMEOUT);
            if (response.statusCode() != 200) throw statusFailure(response.statusCode(), response.body());
            if (response.body().length == 0) throw new OllamaException(EMPTY_RESPONSE);
            String document = decode(response.body());
            var root = OllamaJson.readObject(document);
            var message = root.get("message");
            if (message == null || !message.isObject() || !"assistant".equals(message.path("role").asString())
                    || !root.path("done").isBoolean() || !root.path("done").asBoolean()
                    || !root.path("model").isString() || !settings.model().equals(root.path("model").asString())
                    || (root.has("done_reason") && !"stop".equals(root.path("done_reason").asString())))
                throw new OllamaException(INVALID_RESPONSE);
            var content = message.get("content");
            if (content == null || !content.isString()) throw new OllamaException(INVALID_RESPONSE);
            if (!hasText(content.asString())) throw new OllamaException(EMPTY_RESPONSE);
            if (content.asString().getBytes(StandardCharsets.UTF_8).length > 65_536) throw new OllamaException(RESPONSE_TOO_LARGE);
            return new Response(content.asString(), settings.model(), Duration.ofNanos(System.nanoTime() - started));
        } catch (InterruptedException failure) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new OllamaException(INTERRUPTED);
        } catch (TimeoutException failure) {
            exchange.cancel(true);
            throw new OllamaException(REQUEST_TIMEOUT);
        } catch (ExecutionException failure) {
            exchange.cancel(true);
            Throwable cause = failure.getCause();
            while (cause != null) {
                if (cause instanceof OllamaException safe) throw safe;
                if (cause instanceof HttpTimeoutException) throw new OllamaException(REQUEST_TIMEOUT);
                if (cause instanceof ConnectException) throw new OllamaException(CONNECTION_FAILED);
                cause = cause.getCause();
            }
            throw new OllamaException(CONNECTION_FAILED);
        } catch (RuntimeException failure) {
            if (failure instanceof OllamaException safe) throw safe;
            throw new OllamaException(CONNECTION_FAILED);
        } finally {
            if (!exchange.isDone()) exchange.cancel(true);
        }
    }

    private static OllamaException statusFailure(int status, byte[] body) {
        return new OllamaException(switch (status) {
            case 400 -> HTTP_BAD_REQUEST;
            case 404 -> isMissingModel(body) ? MODEL_NOT_FOUND : HTTP_NOT_FOUND;
            case 500 -> HTTP_SERVER_ERROR;
            case 503 -> HTTP_UNAVAILABLE;
            default -> HTTP_ERROR;
        });
    }

    private static boolean isMissingModel(byte[] body) {
        try {
            var error = OllamaJson.readObject(new String(body, StandardCharsets.UTF_8)).get("error");
            if (error == null || !error.isString()) return false;
            String value = error.asString().toLowerCase(Locale.ROOT);
            return value.contains("model") && value.contains("not found");
        } catch (OllamaException ignored) { return false; }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw new OllamaException(INVALID_RESPONSE); }
    }

    private static boolean hasText(String value) {
        return value != null && value.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c));
    }

    @Override public void close() { closed.set(true); http.shutdownNow(); }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return completed; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); completed.completeExceptionally(new OllamaException(RESPONSE_TOO_LARGE)); return;
                }
                byte[] fragment = new byte[buffer.remaining()]; buffer.get(fragment); bytes.writeBytes(fragment);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { completed.completeExceptionally(failure); }
        @Override public void onComplete() { completed.complete(bytes.toByteArray()); }
    }
}

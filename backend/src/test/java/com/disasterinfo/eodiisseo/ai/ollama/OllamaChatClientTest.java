package com.disasterinfo.eodiisseo.ai.ollama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.disasterinfo.eodiisseo.ai.ollama.OllamaException.Reason.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP transport against synthetic loopback servers; no Ollama/model/server installation required. */
class OllamaChatClientTest {
    private static final String MODEL = "test-model:fixture";
    private static final Map<String,Object> SCHEMA = Map.of("type","object","properties",Map.of("answer",Map.of("type","string")));
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverWorkers;
    private URI base;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestContentType = new AtomicReference<>();
    private final AtomicReference<Handler> handler = new AtomicReference<>();

    @BeforeEach void startLoopbackFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverWorkers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverWorkers);
        handler.set(exchange -> respond(exchange,200,validResponse("test model result")));
        server.createContext("/api/chat",exchange -> {
            requests.incrementAndGet();
            requestMethod.set(exchange.getRequestMethod());
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try { handler.get().handle(exchange); }
            catch (IOException cancelledClient) { /* A cancellation intentionally closes the fixture socket. */ }
            finally { exchange.close(); }
        });
        server.start(); base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stopLoopbackFixture() {
        if (server != null) server.stop(0);
        if (serverWorkers != null) serverWorkers.shutdownNow();
    }

    @Test void sendsConfiguredNonstreamingStructuredChatAndSafelyExtractsAssistantContent() {
        try (var client = client(Duration.ofSeconds(3))) {
            var result = client.chat("test system policy","test approved public question",SCHEMA,Instant.now().plusSeconds(3));
            assertThat(result.content()).isEqualTo("test model result");
            assertThat(result.modelName()).isEqualTo(MODEL);
            assertThat(result.elapsed().isNegative()).isFalse();
            assertThat(result.toString()).doesNotContain(result.content());
            var sent = OllamaJson.readObject(requestBody.get());
            assertThat(requestMethod.get()).isEqualTo("POST");
            assertThat(requestContentType.get()).isEqualTo("application/json");
            assertThat(sent.path("model").asString()).isEqualTo(MODEL);
            assertThat(sent.path("stream").asBoolean()).isFalse();
            assertThat(sent.path("options").path("temperature").asDouble()).isEqualTo(0.2);
            assertThat(sent.path("options").path("num_ctx").asInt()).isEqualTo(4096);
            assertThat(sent.path("format")).isEqualTo(OllamaJson.readObject(OllamaJson.write(SCHEMA)));
            assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("system");
            assertThat(sent.path("messages").get(0).path("content").asString()).isEqualTo("test system policy");
            assertThat(sent.path("messages").get(1).path("role").asString()).isEqualTo("user");
            assertThat(sent.path("messages").get(1).path("content").asString()).isEqualTo("test approved public question");
        }
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest @CsvSource({"400,HTTP_BAD_REQUEST","404,HTTP_NOT_FOUND","500,HTTP_SERVER_ERROR","503,HTTP_UNAVAILABLE"})
    void distinguishesHttpErrorsWithoutExposingProviderBody(int status,OllamaException.Reason reason) {
        handler.set(exchange -> respond(exchange,status,"{\"error\":\"test-sensitive-provider-detail\"}"));
        try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> chat(client),reason); }
    }
    @Test void distinguishesAMissingConfiguredModelFromAnOrdinary404() {
        handler.set(exchange -> respond(exchange,404,"{\"error\":\"model test-model:fixture not found; test-sensitive-provider-detail\"}"));
        try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> chat(client),MODEL_NOT_FOUND); }
    }
    @Test void rejectsAnEmptyBody() {
        handler.set(exchange -> respond(exchange,200,""));
        try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> chat(client),EMPTY_RESPONSE); }
    }
    @ParameterizedTest @ValueSource(strings={
            "not json", "[]", "null", "{}{}",
            "{\"model\":\"test-model:fixture\",\"model\":\"test-model:fixture\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"test\"}}",
            "{\"model\":\"test-model:fixture\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"test\",\"content\":\"replacement\"}}",
            "{\"model\":\"another-model\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"test\"}}",
            "{\"model\":\"test-model:fixture\",\"done\":false,\"message\":{\"role\":\"assistant\",\"content\":\"test\"}}",
            "{\"model\":\"test-model:fixture\",\"done\":\"true\",\"message\":{\"role\":\"assistant\",\"content\":\"test\"}}",
            "{\"model\":\"test-model:fixture\",\"done\":true,\"done_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"test\"}}",
            "{\"model\":\"test-model:fixture\",\"done\":true,\"message\":{\"role\":\"user\",\"content\":\"test\"}}",
            "{\"model\":\"test-model:fixture\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":123}}"
    }) void rejectsMalformedAmbiguousOrIncompleteModelResponses(String body) {
        handler.set(exchange -> respond(exchange,200,body));
        try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> chat(client),INVALID_RESPONSE); }
    }
    @ParameterizedTest @ValueSource(strings={""," ","\u00a0"})
    void rejectsEmptyOrUnicodeWhitespaceOnlyAssistantContent(String content) {
        handler.set(exchange -> respond(exchange,200,validResponse(content)));
        try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> chat(client),EMPTY_RESPONSE); }
    }
    @Test void rejectsMalformedUtf8InsteadOfReplacingInvalidBytesInModelOutput() {
        handler.set(exchange -> {
            var bytes = new java.io.ByteArrayOutputStream();
            bytes.writeBytes(("{\"model\":\""+MODEL+"\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"").getBytes(StandardCharsets.US_ASCII));
            bytes.writeBytes(new byte[]{(byte)0xc3,(byte)0x28});
            bytes.writeBytes("\"}}".getBytes(StandardCharsets.US_ASCII));
            exchange.sendResponseHeaders(200,bytes.size()); exchange.getResponseBody().write(bytes.toByteArray());
        });
        try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> chat(client),INVALID_RESPONSE); }
    }
    @Test void validBoundedEnvelopeMayBeLargerThanTheContentAndNeverExposesModelThinking() {
        handler.set(exchange -> respond(exchange,200,OllamaJson.write(Map.of("model",MODEL,"done",true,"done_reason","stop",
                "message",Map.of("role","assistant","content","test safe result","thinking","test-unexposed-model-thinking".repeat(3_000))))));
        try (var client = client(Duration.ofSeconds(3))) {
            var result = client.chat("test policy","test approved input",SCHEMA,null);
            assertThat(result.content()).isEqualTo("test safe result");
            assertThat(result.toString()).doesNotContain("test-unexposed-model-thinking");
        }
    }
    @Test void capsUtf8ContentBytesAsWellAsCharacterCount() {
        handler.set(exchange -> respond(exchange,200,validResponse("가".repeat(22_000))));
        try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> chat(client),RESPONSE_TOO_LARGE); }
    }
    @Test void rejectsOversizedResponseBodyBeforeAccumulatingAnUnboundedDocument() {
        handler.set(exchange -> respond(exchange,200," ".repeat(1_048_577)));
        try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> chat(client),RESPONSE_TOO_LARGE); }
    }
    @Test void expiredDeadlinePreventsAnyHttpRequest() {
        try (var client = client(Duration.ofSeconds(3))) {
            assertFailure(() -> client.chat("test policy","test approved input",SCHEMA,Instant.now().minusSeconds(1)),REQUEST_TIMEOUT);
        }
        assertThat(requests).hasValue(0);
    }
    @Test void oversizedInputFailsBeforeThePromptIsSent() {
        try (var client = client(Duration.ofSeconds(3))) {
            assertFailure(() -> client.chat("test policy","가".repeat(22_000),SCHEMA,null),CONTEXT_LIMIT_EXCEEDED);
        }
        assertThat(requests).hasValue(0);
    }
    @Test void limitsTheConfiguredRequestWaitWhenResponseHeadersAreDelayed() {
        handler.set(exchange -> { pause(Duration.ofMillis(300)); respond(exchange,200,validResponse("test result")); });
        try (var client = client(Duration.ofMillis(80))) { assertFailure(() -> chat(client),REQUEST_TIMEOUT); }
    }
    @Test void capsTheEntireBodyWaitByTheRemainingCallerDeadline() {
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200,10_000); exchange.getResponseBody().flush();
            pause(Duration.ofMillis(300)); exchange.getResponseBody().write(validResponse("test result").getBytes(StandardCharsets.UTF_8));
        });
        long started = System.nanoTime();
        try (var client = client(Duration.ofSeconds(3))) {
            assertFailure(() -> client.chat("test policy","test approved input",SCHEMA,Instant.now().plusMillis(80)),REQUEST_TIMEOUT);
        }
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));
    }
    @Test void interruptedWaitCancelsTheActualHttpExchangeAndPreservesTheInterruptFlag() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicBoolean interrupted = new AtomicBoolean();
        try (var listener = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
             var peer = Executors.newSingleThreadExecutor();
             var client = new OllamaChatClient(settings(URI.create("http://127.0.0.1:"+listener.getLocalPort()),Duration.ofSeconds(5)))) {
            listener.setSoTimeout(4_000);
            var sawSocketClose = peer.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(4_000); readHttpRequest(socket);
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush(); bodyStarted.countDown();
                    try { return socket.getInputStream().read() == -1; }
                    catch (java.net.SocketException resetByCancelledClient) { return true; }
                }
            });
            Thread caller = Thread.ofVirtual().start(() -> {
                try { client.chat("test policy","test-sensitive-approved-prompt",SCHEMA,Instant.now().plusSeconds(5)); }
                catch (Throwable result) { failure.set(result); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            try {
                assertThat(bodyStarted.await(3,TimeUnit.SECONDS)).isTrue(); caller.interrupt(); caller.join(3_000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(failure.get()).isInstanceOfSatisfying(OllamaException.class,result -> {
                    assertThat(result.reason()).isEqualTo(INTERRUPTED);
                    assertThat(result.getCause()).isNull(); assertThat(result.getMessage()).doesNotContain("test-sensitive-approved-prompt");
                });
                assertThat(interrupted).isTrue();
                assertThat(sawSocketClose.get(3,TimeUnit.SECONDS)).isTrue();
            } finally { caller.interrupt(); }
        }
    }
    @Test void distinguishesConnectionRefusalWithoutLeakingConnectionDetails() throws Exception {
        int unusedPort;
        try (var reservation = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { unusedPort = reservation.getLocalPort(); }
        try (var client = new OllamaChatClient(settings(URI.create("http://127.0.0.1:"+unusedPort),Duration.ofSeconds(2)))) {
            assertFailure(() -> chat(client),CONNECTION_FAILED);
        }
    }
    @Test void doesNotFollowRedirectsOrSendPromptsToTheRedirectTarget() throws Exception {
        AtomicInteger redirectedRequests = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        target.createContext("/",exchange -> { redirectedRequests.incrementAndGet(); respond(exchange,200,validResponse("test redirect result")); exchange.close(); });
        target.start();
        try {
            handler.set(exchange -> { exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+target.getAddress().getPort()+"/capture"); respond(exchange,307,"test redirect"); });
            try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> chat(client),HTTP_ERROR); }
            assertThat(redirectedRequests).hasValue(0);
        } finally { target.stop(0); }
    }
    @Test void closingTheClientImmediatelyDisablesFurtherCalls() {
        var client = client(Duration.ofSeconds(3)); assertThat(client.isConfigured()).isTrue(); client.close();
        assertThat(client.isConfigured()).isFalse(); assertFailure(() -> chat(client),INVALID_INPUT);
        assertThat(requests).hasValue(0);
    }
    @ParameterizedTest @ValueSource(strings={""," ","\u00a0"})
    void rejectsBlankPromptInputWithoutMakingRequests(String prompt) {
        try (var client = client(Duration.ofSeconds(3))) { assertFailure(() -> client.chat("test policy",prompt,SCHEMA,null),INVALID_INPUT); }
        assertThat(requests).hasValue(0);
    }

    private OllamaChatClient client(Duration timeout) { return new OllamaChatClient(settings(base,timeout)); }
    private static OllamaSettings settings(URI base,Duration timeout) { return new OllamaSettings(base,MODEL,timeout,Duration.ofSeconds(1),Set.of("127.0.0.1")); }
    private static void chat(OllamaChatClient client) { client.chat("test policy","test-sensitive-approved-prompt",SCHEMA,null); }
    private static void assertFailure(Runnable action,OllamaException.Reason expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OllamaException.class,failure -> {
            assertThat(failure.reason()).isEqualTo(expected); assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("test-sensitive-provider-detail","test-sensitive-approved-prompt","127.0.0.1");
            assertThat(failure.toString()).doesNotContain("test-sensitive-provider-detail","test-sensitive-approved-prompt");
        });
    }
    private static String validResponse(String content) {
        return OllamaJson.write(Map.of("model",MODEL,"done",true,"done_reason","stop","message",Map.of("role","assistant","content",content)));
    }
    private static void respond(HttpExchange exchange,int status,String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json");
        if (bytes.length == 0) exchange.sendResponseHeaders(status,-1);
        else { exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); }
    }
    private static void pause(Duration duration) { java.util.concurrent.locks.LockSupport.parkNanos(duration.toNanos()); }
    private static void readHttpRequest(Socket socket) throws IOException {
        var input = socket.getInputStream(); var header = new java.io.ByteArrayOutputStream(); int suffix = 0;
        while (suffix != 0x0d0a0d0a) {
            int next = input.read(); if (next < 0 || header.size()>65_536) throw new IOException("Incomplete fixture request.");
            header.write(next); suffix = (suffix<<8)|next;
        }
        String text = header.toString(StandardCharsets.US_ASCII); int length = 0;
        for (String line : text.split("\r\n")) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':')+1).strip());
        if (input.readNBytes(length).length != length) throw new IOException("Incomplete fixture request body.");
    }
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}

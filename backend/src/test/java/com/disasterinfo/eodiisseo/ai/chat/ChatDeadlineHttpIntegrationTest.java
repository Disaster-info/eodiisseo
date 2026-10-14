package com.disasterinfo.eodiisseo.ai.chat;

import com.disasterinfo.eodiisseo.ai.ollama.OllamaChatClient;
import com.disasterinfo.eodiisseo.ai.ollama.OllamaSettings;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real JDK HTTP cancellation through ChatDeadline; no model or production backend is invoked. */
class ChatDeadlineHttpIntegrationTest {

    private static final Duration LONG_HTTP_BUDGET = Duration.ofSeconds(15);
    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    @Test
    void interruptingTheOuterRequestCancelsTheRealPendingHttpBodyAndClosesItsSocket() throws Exception {
        try (StalledBodyServer server = new StalledBodyServer(); OllamaChatClient client = client(server)) {
            ChatDeadline deadline = new ChatDeadline(Clock.systemUTC(), Instant.now().plusSeconds(10));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch returned = new CountDownLatch(1);
            Thread caller = startCaller(deadline, client, failure, returned);
            try {
                assertThat(server.headersSent.await(5, TimeUnit.SECONDS)).isTrue();
                caller.interrupt();

                assertThat(returned.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get()).isInstanceOf(InterruptedException.class);
                assertThat(server.clientDisconnected.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(server.failure.get()).isNull();
            } finally {
                caller.interrupt();
                caller.join(2_000);
            }
        }
    }

    @Test
    void theShortOuterTimeoutCancelsHttpBeforeItsLongerTransportDeadline() throws Exception {
        try (StalledBodyServer server = new StalledBodyServer(); OllamaChatClient client = client(server)) {
            ChatDeadline deadline = new ChatDeadline(Clock.systemUTC(), Instant.now().plusSeconds(3));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch returned = new CountDownLatch(1);
            Thread caller = startCaller(deadline, client, failure, returned);
            try {
                assertThat(server.headersSent.await(2, TimeUnit.SECONDS)).isTrue();

                assertThat(returned.await(4, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get()).isInstanceOf(TimeoutException.class);
                assertThat(server.clientDisconnected.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(server.failure.get()).isNull();
            } finally {
                caller.interrupt();
                caller.join(2_000);
            }
        }
    }

    private static Thread startCaller(ChatDeadline deadline, OllamaChatClient client,
                                      AtomicReference<Throwable> failure, CountDownLatch returned) {
        return Thread.ofVirtual().name("chat-deadline-real-http-test").start(() -> {
            try {
                deadline.await(() -> client.chat("Fixture policy", "Approved public fixture question", SCHEMA,
                        Instant.now().plus(LONG_HTTP_BUDGET)));
                failure.set(new AssertionError("A stalled body cannot produce a successful response."));
            } catch (Throwable result) {
                failure.set(result);
            } finally {
                returned.countDown();
            }
        });
    }

    private static OllamaChatClient client(StalledBodyServer server) {
        return new OllamaChatClient(new OllamaSettings(server.baseUrl(), "test-cancellation:fixture",
                LONG_HTTP_BUDGET, Duration.ofSeconds(2), Set.of("127.0.0.1")));
    }

    /** Sends headers but no response body, then observes EOF/RST caused by client cancellation. */
    private static final class StalledBodyServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private final AtomicReference<Socket> accepted = new AtomicReference<>();
        private final CountDownLatch headersSent = new CountDownLatch(1);
        private final CountDownLatch clientDisconnected = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        StalledBodyServer() throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(5_000);
            worker = Thread.ofVirtual().name("chat-stalled-body-fixture").start(this::serve);
        }

        URI baseUrl() {
            return URI.create("http://127.0.0.1:" + listener.getLocalPort());
        }

        private void serve() {
            try (Socket socket = listener.accept()) {
                accepted.set(socket);
                socket.setSoTimeout(8_000);
                var input = socket.getInputStream();
                var headers = new ByteArrayOutputStream();
                while (true) {
                    int next = input.read();
                    if (next < 0 || headers.size() >= 16_384) throw new IOException("Invalid fixture request headers.");
                    headers.write(next);
                    byte[] bytes = headers.toByteArray();
                    int size = bytes.length;
                    if (size >= 4 && bytes[size - 4] == '\r' && bytes[size - 3] == '\n'
                            && bytes[size - 2] == '\r' && bytes[size - 1] == '\n') break;
                }
                int contentLength = -1;
                for (String line : headers.toString(StandardCharsets.US_ASCII).split("\r\n")) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring("content-length:".length()).strip());
                    }
                }
                if (contentLength < 0 || contentLength > 65_536 || input.readNBytes(contentLength).length != contentLength) {
                    throw new IOException("Incomplete fixture request body.");
                }
                var output = socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 4096\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                headersSent.countDown();
                try {
                    if (input.read() != -1) throw new IOException("Unexpected fixture connection input.");
                    clientDisconnected.countDown();
                } catch (SocketException resetByCancellation) {
                    clientDisconnected.countDown();
                }
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
        }

        @Override
        public void close() throws Exception {
            listener.close();
            Socket socket = accepted.get();
            if (socket != null) socket.close();
            worker.interrupt();
            worker.join(2_000);
        }
    }
}

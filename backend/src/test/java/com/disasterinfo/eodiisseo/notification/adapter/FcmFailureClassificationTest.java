package com.disasterinfo.eodiisseo.notification.adapter;

import com.disasterinfo.eodiisseo.notification.NotificationContracts.Push;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.PushOutcome;
import com.disasterinfo.eodiisseo.notification.NotificationContracts.PushResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Classification only: injected transports never contact Google or read credential files. */
class FcmFailureClassificationTest {
    private static final String DEVICE = "test-only-registration";
    private static final Push PUSH = new Push(UUID.randomUUID(), "TEST_ONLY", "test region",
            "test official title", "test official content");

    private static ServiceAccountFcmTokens tokens() {
        var tokens = mock(ServiceAccountFcmTokens.class);
        when(tokens.isConfigured()).thenReturn(true);
        when(tokens.accessToken()).thenReturn("test-only-oauth");
        when(tokens.projectId()).thenReturn("example-project");
        return tokens;
    }

    static Stream<Arguments> rejectedStatuses() {
        return Stream.of(
                Arguments.of(400, PushOutcome.INVALID_REQUEST),
                Arguments.of(401, PushOutcome.AUTHORIZATION_ERROR),
                Arguments.of(403, PushOutcome.AUTHORIZATION_ERROR),
                Arguments.of(404, PushOutcome.PROVIDER_REJECTED),
                Arguments.of(429, PushOutcome.RATE_LIMITED),
                Arguments.of(500, PushOutcome.TRANSIENT_FAILURE),
                Arguments.of(503, PushOutcome.TRANSIENT_FAILURE));
    }

    @ParameterizedTest
    @MethodSource("rejectedStatuses")
    void definitiveHttpFailuresRemainClassifiedWithoutTrustingProviderProse(int status, PushOutcome expected) {
        AtomicInteger calls = new AtomicInteger();
        var sender = new FcmHttpV1Sender(tokens(), (uri, bearer, body) -> {
            calls.incrementAndGet();
            return new FcmHttpV1Sender.Response(status, "not-json private-provider-detail".getBytes(StandardCharsets.UTF_8));
        });

        PushResult result = sender.send(DEVICE, PUSH);

        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.successful()).isFalse();
        assertThat(result.invalidToken()).isFalse();
        assertThat(result.providerMessageId()).isNull();
        assertThat(result.toString()).doesNotContain("private-provider-detail", DEVICE, "test-only-oauth");
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    void explicitUnregisteredRequiresTheMatching404Status(int status) {
        String body = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\","
                + "\"errorCode\":\"UNREGISTERED\"}]}}";
        var sender = new FcmHttpV1Sender(tokens(), (uri, bearer, payload) ->
                new FcmHttpV1Sender.Response(status, body.getBytes(StandardCharsets.UTF_8)));

        PushResult result = sender.send(DEVICE, PUSH);

        assertThat(result.invalidToken()).isEqualTo(status == 404);
        assertThat(result.outcome() == PushOutcome.UNREGISTERED).isEqualTo(status == 404);
    }

    @Test
    void unregisteredTextWithoutTypedFcmDetailCannotInvalidateADevice() {
        String body = "{\"error\":{\"status\":\"UNREGISTERED\",\"message\":\"UNREGISTERED\","
                + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.BadRequest\",\"errorCode\":\"UNREGISTERED\"}]}}";
        var sender = new FcmHttpV1Sender(tokens(), (uri, bearer, payload) ->
                new FcmHttpV1Sender.Response(404, body.getBytes(StandardCharsets.UTF_8)));

        PushResult result = sender.send(DEVICE, PUSH);

        assertThat(result.outcome()).isEqualTo(PushOutcome.PROVIDER_REJECTED);
        assertThat(result.invalidToken()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "[]", "{\"name\":\"projects/other-project/messages/123\"}"})
    void unverifiableSuccessBodyIsAcceptanceUnknownRatherThanProofOfRejection(String body) {
        var sender = new FcmHttpV1Sender(tokens(), (uri, bearer, payload) ->
                new FcmHttpV1Sender.Response(200, body.getBytes(StandardCharsets.UTF_8)));

        assertThat(sender.send(DEVICE, PUSH).outcome()).isEqualTo(PushOutcome.ACCEPTANCE_UNKNOWN);
    }

    @Test
    void missingOrOversizedSuccessBodyCannotBeTreatedAsAccepted() {
        for (byte[] body : new byte[][]{null, new byte[16_385]}) {
            var sender = new FcmHttpV1Sender(tokens(), (uri, bearer, payload) ->
                    new FcmHttpV1Sender.Response(200, body));
            assertThat(sender.send(DEVICE, PUSH).outcome()).isEqualTo(PushOutcome.ACCEPTANCE_UNKNOWN);
        }
        var missing = new FcmHttpV1Sender(tokens(), (uri, bearer, payload) -> null);
        assertThat(missing.send(DEVICE, PUSH).outcome()).isEqualTo(PushOutcome.ACCEPTANCE_UNKNOWN);
    }

    static Stream<Exception> uncertainTransportFailures() {
        return Stream.of(new HttpTimeoutException("private-timeout-detail"),
                new SocketTimeoutException("private-timeout-detail"),
                new IOException("private-connection-detail"),
                new IllegalStateException("private-transport-detail"));
    }

    @ParameterizedTest
    @MethodSource("uncertainTransportFailures")
    void transportUncertaintyNeverRetriesPostOrLeaksFailureDetails(Exception failure) {
        AtomicInteger calls = new AtomicInteger();
        var sender = new FcmHttpV1Sender(tokens(), (uri, bearer, payload) -> {
            calls.incrementAndGet();
            throw failure;
        });

        PushResult result = sender.send(DEVICE, PUSH);

        boolean timeout = failure instanceof HttpTimeoutException || failure instanceof SocketTimeoutException;
        assertThat(result.outcome()).isEqualTo(timeout ? PushOutcome.NETWORK_TIMEOUT : PushOutcome.ACCEPTANCE_UNKNOWN);
        assertThat(result.invalidToken()).isFalse();
        assertThat(result.successful()).isFalse();
        assertThat(result.toString()).doesNotContain("private-", DEVICE, "test-only-oauth");
        assertThat(calls).hasValue(1);
    }

    @Test
    void missingOAuthNeverAttemptsAnFcmSubmission() {
        var tokens = tokens();
        when(tokens.accessToken()).thenReturn(null);
        var sender = new FcmHttpV1Sender(tokens, (uri, bearer, payload) -> {
            throw new AssertionError("No FCM submission is permitted without OAuth");
        });

        assertThat(sender.send(DEVICE, PUSH).outcome()).isEqualTo(PushOutcome.AUTHENTICATION_UNAVAILABLE);
    }

    @Test
    void disabledSenderAndInvalidInputFailBeforeOAuth() {
        var tokens = tokens();
        var sender = new FcmHttpV1Sender(tokens, (uri, bearer, payload) -> {
            throw new AssertionError("No FCM submission is permitted");
        });
        when(tokens.isConfigured()).thenReturn(false);
        assertThat(sender.send(DEVICE, PUSH).outcome()).isEqualTo(PushOutcome.UNAVAILABLE);
        when(tokens.isConfigured()).thenReturn(true);
        assertThat(sender.send("\u00a0", PUSH).outcome()).isEqualTo(PushOutcome.INVALID_REQUEST);
        verify(tokens, never()).accessToken();
    }

    @Test
    void interruptedSubmissionKeepsInterruptFlagAndUnknownAcceptance() {
        boolean previous = Thread.interrupted();
        try {
            var sender = new FcmHttpV1Sender(tokens(), (uri, bearer, payload) -> {
                throw new InterruptedException("private-interruption-detail");
            });
            assertThat(sender.send(DEVICE, PUSH).outcome()).isEqualTo(PushOutcome.ACCEPTANCE_UNKNOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            if (previous) Thread.currentThread().interrupt();
        }
    }

    @Test
    void legacyFailureCarriesNoEvidenceOfProviderRejection() {
        assertThat(new PushResult(false, false, null).outcome()).isEqualTo(PushOutcome.ACCEPTANCE_UNKNOWN);
        assertThat(new PushResult(false, true, null).outcome()).isEqualTo(PushOutcome.UNREGISTERED);
        assertThat(new PushResult(true, false, "test-provider-name").outcome()).isEqualTo(PushOutcome.ACCEPTED);
    }
}
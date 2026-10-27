package com.disasterinfo.eodiisseo.ai.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatDeadlineTest {

    @Test
    void blockedProviderIsInterruptedAndCallerReturnsWithoutWaitingForTheProvider() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ChatDeadline deadline = new ChatDeadline(Clock.systemUTC(), Instant.now().plusMillis(100));

        assertThatThrownBy(() -> deadline.await(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return "late answer";
        })).isInstanceOf(TimeoutException.class);

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void expiredDeadlineDoesNotStartAnyProviderTask() {
        ChatDeadline deadline = new ChatDeadline(Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC), Instant.EPOCH);
        assertThatThrownBy(() -> deadline.await(() -> { throw new AssertionError("Must not be called"); }))
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    void documentedLimitIsTenMinutesForTheEntireRequest() {
        assertThat(ChatService.RESPONSE_LIMIT).isEqualTo(Duration.ofMinutes(10));
    }
}

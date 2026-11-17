package com.disasterinfo.eodiisseo.ai.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Request-local virtual tasks; timeout cancellation never waits for a misbehaving provider. */
final class ChatDeadline {

    private final Clock clock;
    private final Instant deadline;

    ChatDeadline(Clock clock, Instant deadline) {
        this.clock = clock;
        this.deadline = deadline;
    }

    <T> T await(Callable<T> action) throws TimeoutException, ExecutionException, InterruptedException {
        long remainingNanos = remainingNanos();
        FutureTask<T> task = new FutureTask<>(action);
        Thread.ofVirtual().name("chat-evidence-generation").start(task);
        try {
            T result = task.get(remainingNanos, TimeUnit.NANOSECONDS);
            remainingNanos();
            return result;
        } catch (ExecutionException failure) {
            remainingNanos();
            throw failure;
        } catch (TimeoutException | InterruptedException failure) {
            task.cancel(true);
            throw failure;
        }
    }

    private long remainingNanos() throws TimeoutException {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new TimeoutException("AI guidance request deadline elapsed.");
        }
        return remaining.toNanos();
    }
}

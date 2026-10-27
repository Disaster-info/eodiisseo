package com.disasterinfo.eodiisseo.ai.summary.port;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Documents mandate limited retries without fixing a count or delay. No invented defaults. */
public interface SummaryRetryPolicy {
    Optional<Policy> configuredPolicy();

    record Policy(int maxRetries, Duration retryDelay) {
        public Policy {
            Objects.requireNonNull(retryDelay);
            if (maxRetries < 0 || maxRetries > Short.MAX_VALUE || retryDelay.isNegative()) {
                throw new IllegalArgumentException("A finite nonnegative retry policy is required.");
            }
        }
    }
}

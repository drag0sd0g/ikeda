package com.ikeda.support;

import java.time.Duration;

/**
 * Paces outbound requests. Injected rather than hard-coded so tests do not sleep.
 */
@FunctionalInterface
public interface RateLimiter {

    void acquire() throws InterruptedException;

    /** No pacing at all. For tests. */
    static RateLimiter none() {
        return () -> { };
    }

    /** Guarantees at least {@code interval} between consecutive acquisitions. */
    static RateLimiter minInterval(Duration interval) {
        return new MinInterval(interval);
    }

    final class MinInterval implements RateLimiter {

        private final long intervalMillis;
        private long lastAcquiredAt;

        private MinInterval(Duration interval) {
            this.intervalMillis = interval.toMillis();
        }

        @Override
        public synchronized void acquire() throws InterruptedException {
            long waitFor = intervalMillis - (System.currentTimeMillis() - lastAcquiredAt);
            if (waitFor > 0) {
                Thread.sleep(waitFor);
            }
            lastAcquiredAt = System.currentTimeMillis();
        }
    }
}

package com.ikeda.support;

import java.time.Duration;

@FunctionalInterface
public interface RateLimiter {
    void acquire() throws InterruptedException;

    static RateLimiter none() {
        return () -> { };
    }

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

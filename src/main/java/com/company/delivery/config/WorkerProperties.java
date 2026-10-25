package com.company.delivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("delivery.worker")
public record WorkerProperties(
        int concurrency,
        Duration lease,
        int maxAttempts,
        Duration maxAge,
        Duration initialBackoff,
        Duration maxBackoff) {
    public WorkerProperties {
        if (concurrency < 1 || concurrency > 32 || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException(
                    "Worker concurrency must be 1–32 and maxAttempts 1–100");
        }
        for (Duration value : new Duration[] {lease, maxAge, initialBackoff, maxBackoff}) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("Worker durations must be positive");
            }
        }
        if (initialBackoff.compareTo(maxBackoff) > 0
                || maxBackoff.compareTo(maxAge) > 0
                || maxAge.compareTo(Duration.ofHours(23)) > 0) {
            throw new IllegalArgumentException(
                    "Backoff bounds invalid or retry horizon exceeds Alpha retention");
        }
    }
}

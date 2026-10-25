package com.company.delivery.service;

import com.company.delivery.client.CarrierOutcome;
import com.company.delivery.config.WorkerProperties;
import com.company.delivery.model.BookingStatus;
import com.company.delivery.model.Carrier;
import com.company.delivery.model.ClaimedJob;
import com.company.delivery.model.Decision;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

@Component
public class RetryPolicy {

    private final WorkerProperties properties;
    private final Clock clock;
    private final DoubleSupplier jitter;

    @Autowired
    public RetryPolicy(WorkerProperties properties) {
        this(properties, Clock.systemUTC(), () -> ThreadLocalRandom.current().nextDouble());
    }

    RetryPolicy(WorkerProperties properties, Clock clock, DoubleSupplier jitter) {
        this.properties = properties;
        this.clock = clock;
        this.jitter = jitter;
    }

    public Optional<Decision> beforeCall(ClaimedJob job) {
        if (job.actionAttempt() > properties.maxAttempts()
                || !clock.instant().isBefore(deadline(job))) {
            return Optional.of(
                    exhausted(
                            job,
                            CarrierOutcome.of(
                                    job.uncertain()
                                            ? CarrierOutcome.Kind.UNKNOWN
                                            : CarrierOutcome.Kind.RETRYABLE,
                                    "RETRY_EXHAUSTED")));
        }
        return Optional.empty();
    }

    public Decision afterCall(ClaimedJob job, CarrierOutcome result) {
        return switch (result.kind()) {
            case SUCCESS -> Decision.terminal(BookingStatus.BOOKED, result);
            case CONFIG_ERROR, REJECTED ->
                    Decision.terminal(terminalFailureStatus(job, result), result);
            case UNKNOWN, NOT_FOUND, RETRYABLE -> retry(job, result);
        };
    }

    private Decision retry(ClaimedJob job, CarrierOutcome result) {
        ClaimedJob.Action next = nextAction(job, result);
        Duration delay = retryDelay(job.actionAttempt(), result.retryAfter());
        if (isRetryBudgetExhausted(job, next, delay)) {
            return exhausted(job, result);
        }
        return new Decision(
                next == ClaimedJob.Action.LOOKUP
                        ? BookingStatus.RECONCILING
                        : BookingStatus.RETRY_SCHEDULED,
                next,
                delay,
                result);
    }

    private BookingStatus terminalFailureStatus(ClaimedJob job, CarrierOutcome result) {
        if (hasUncertainOutcome(job, result)) {
            return BookingStatus.REQUIRES_RECONCILIATION;
        }
        if (result.kind() == CarrierOutcome.Kind.REJECTED) {
            return BookingStatus.REJECTED;
        }
        return BookingStatus.FAILED;
    }

    private ClaimedJob.Action nextAction(ClaimedJob job, CarrierOutcome result) {
        if (job.action() == ClaimedJob.Action.LOOKUP) {
            if (job.carrier() == Carrier.ALPHA && result.kind() == CarrierOutcome.Kind.NOT_FOUND) {
                return ClaimedJob.Action.CREATE;
            }
            return ClaimedJob.Action.LOOKUP;
        }
        if (job.carrier() == Carrier.BETA && result.kind() == CarrierOutcome.Kind.UNKNOWN) {
            return ClaimedJob.Action.LOOKUP;
        }
        return ClaimedJob.Action.CREATE;
    }

    private Duration retryDelay(int attempt, Duration retryAfter) {
        Duration delay = backoff(attempt);
        return retryAfter.compareTo(delay) > 0 ? retryAfter : delay;
    }

    private boolean isRetryBudgetExhausted(
            ClaimedJob job, ClaimedJob.Action next, Duration delay) {
        boolean attemptsExhausted =
                next == job.action() && job.actionAttempt() >= properties.maxAttempts();
        // Retry-After is never shortened. End if the next attempt would reach the deadline.
        Duration remaining = Duration.between(clock.instant(), deadline(job));
        boolean retryReachesDeadline = delay.compareTo(remaining) >= 0;
        return attemptsExhausted || retryReachesDeadline;
    }

    private boolean hasUncertainOutcome(ClaimedJob job, CarrierOutcome result) {
        return job.uncertain()
                || job.action() == ClaimedJob.Action.LOOKUP
                || result.kind() == CarrierOutcome.Kind.UNKNOWN;
    }

    private Instant deadline(ClaimedJob job) {
        return job.createdAt().plus(properties.maxAge());
    }

    private Decision exhausted(ClaimedJob job, CarrierOutcome result) {
        return Decision.terminal(
                hasUncertainOutcome(job, result)
                        ? BookingStatus.REQUIRES_RECONCILIATION
                        : BookingStatus.FAILED,
                new CarrierOutcome(result.kind(), null, null, "RETRY_EXHAUSTED", Duration.ZERO));
    }

    private Duration backoff(int attempt) {
        long cap = properties.maxBackoff().toMillis();
        long base = properties.initialBackoff().toMillis();
        for (int i = 1; i < attempt && base < cap; i++) {
            base = Math.min(cap, base * 2);
        }
        return Duration.ofMillis(
                Math.max(1, (long) (Math.min(base, cap) * (0.5 + jitter.getAsDouble() * 0.5))));
    }
}

package com.company.delivery.service;

import static com.company.delivery.support.BookingFixtures.request;

import static org.assertj.core.api.Assertions.*;

import com.company.delivery.client.CarrierOutcome;
import com.company.delivery.config.WorkerProperties;
import com.company.delivery.model.BookingStatus;
import com.company.delivery.model.Carrier;
import com.company.delivery.model.ClaimedJob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.*;
import java.util.UUID;

class RetryPolicyTest {

    private final Instant now = Instant.parse("2026-10-22T12:00:00Z");
    private final RetryPolicy policy =
            new RetryPolicy(
                    new WorkerProperties(
                            1,
                            Duration.ofSeconds(30),
                            3,
                            Duration.ofHours(1),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(10)),
                    Clock.fixed(now, ZoneOffset.UTC),
                    () -> 0.5);

    ClaimedJob job(Carrier carrier, ClaimedJob.Action action, int attempt, boolean uncertain) {
        return job(carrier, action, attempt, uncertain, now);
    }

    private ClaimedJob job(
            Carrier carrier, ClaimedJob.Action action, int attempt, boolean uncertain,
            Instant createdAt) {
        return new ClaimedJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                action,
                carrier,
                request(1, "PL"),
                createdAt,
                now,
                attempt,
                uncertain);
    }

    @ParameterizedTest
    @CsvSource(value = {
        "ALPHA, LOOKUP, NOT_FOUND, true, RETRY_SCHEDULED, CREATE",
        "ALPHA, LOOKUP, RETRYABLE, true, RECONCILING, LOOKUP",
        "BETA, CREATE, RETRYABLE, false, RETRY_SCHEDULED, CREATE",
        "ALPHA, CREATE, REJECTED, false, REJECTED, NONE",
        "BETA, CREATE, CONFIG_ERROR, false, FAILED, NONE",
        "ALPHA, CREATE, REJECTED, true, REQUIRES_RECONCILIATION, NONE",
        "BETA, LOOKUP, CONFIG_ERROR, false, REQUIRES_RECONCILIATION, NONE",
        "BETA, LOOKUP, SUCCESS, true, BOOKED, NONE"
    }, nullValues = "NONE")
    void appliesCarrierSafetyRules(
            Carrier carrier, ClaimedJob.Action action, CarrierOutcome.Kind kind,
            boolean uncertain, BookingStatus expectedStatus, ClaimedJob.Action expectedNext) {
        var result = kind == CarrierOutcome.Kind.SUCCESS
                ? CarrierOutcome.success("TRACK", "https://labels.example/test")
                : CarrierOutcome.of(kind, "PROVIDER_RESULT");

        var decision = policy.afterCall(job(carrier, action, 1, uncertain), result);

        assertThat(decision.status()).isEqualTo(expectedStatus);
        assertThat(decision.nextAction()).isEqualTo(expectedNext);
        assertThat(decision.outcome()).isSameAs(result);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void doesNotScheduleRetryAtTheDeadline(boolean uncertain) {
        var job = job(
                Carrier.ALPHA, ClaimedJob.Action.CREATE, 1, uncertain,
                now.minus(Duration.ofHours(1)).plusSeconds(1));
        var result = new CarrierOutcome(
                CarrierOutcome.Kind.RETRYABLE, null, null, "RATE_LIMIT", Duration.ofSeconds(1));

        var decision = policy.afterCall(job, result);

        assertThat(decision.status()).isEqualTo(
                uncertain ? BookingStatus.REQUIRES_RECONCILIATION : BookingStatus.FAILED);
        assertThat(decision.nextAction()).isNull();
        assertThat(decision.outcome().code()).isEqualTo("RETRY_EXHAUSTED");
    }

    @Test
    void exhaustedCreateBudgetStillAllowsLookupToResolveUnknownResult() {
        var decision = policy.afterCall(
                job(Carrier.BETA, ClaimedJob.Action.CREATE, 3, false),
                CarrierOutcome.of(CarrierOutcome.Kind.UNKNOWN, "TIMEOUT"));

        assertThat(decision.status()).isEqualTo(BookingStatus.RECONCILING);
        assertThat(decision.nextAction()).isEqualTo(ClaimedJob.Action.LOOKUP);
        assertThat(policy.beforeCall(job(Carrier.BETA, ClaimedJob.Action.LOOKUP, 1, true)))
                .isEmpty();
    }

    @Test
    void betaNeverRecreatesAfterAmbiguousCreate() {
        var decision =
                policy.afterCall(
                        job(Carrier.BETA, ClaimedJob.Action.CREATE, 1, false),
                        CarrierOutcome.of(CarrierOutcome.Kind.UNKNOWN, "TIMEOUT"));
        assertThat(decision.nextAction()).isEqualTo(ClaimedJob.Action.LOOKUP);
        var lookup =
                policy.afterCall(
                        job(Carrier.BETA, ClaimedJob.Action.LOOKUP, 1, true),
                        CarrierOutcome.of(CarrierOutcome.Kind.NOT_FOUND, "NOT_FOUND"));
        assertThat(lookup.nextAction()).isEqualTo(ClaimedJob.Action.LOOKUP);
    }

    @Test
    void retainsUncertaintyEvenAfterLaterSafeFailure() {
        var decision =
                policy.afterCall(
                        job(Carrier.ALPHA, ClaimedJob.Action.CREATE, 3, true),
                        CarrierOutcome.of(CarrierOutcome.Kind.RETRYABLE, "RATE_LIMIT"));
        assertThat(decision.status()).isEqualTo(BookingStatus.REQUIRES_RECONCILIATION);
    }

    @Test
    void respectsRetryAfterAndStopsAtBudget() {
        var result =
                new CarrierOutcome(
                        CarrierOutcome.Kind.RETRYABLE,
                        null,
                        null,
                        "RATE_LIMIT",
                        Duration.ofSeconds(20));
        assertThat(
                        policy.afterCall(
                                        job(Carrier.ALPHA, ClaimedJob.Action.CREATE, 1, false),
                                        result)
                                .delay())
                .isEqualTo(Duration.ofSeconds(20));
        assertThat(
                        policy.afterCall(
                                        job(Carrier.ALPHA, ClaimedJob.Action.CREATE, 3, false),
                                        result)
                                .status())
                .isEqualTo(BookingStatus.FAILED);
        var huge =
                new CarrierOutcome(
                        CarrierOutcome.Kind.RETRYABLE,
                        null,
                        null,
                        "RATE_LIMIT",
                        Duration.ofDays(2));
        assertThat(
                        policy.afterCall(
                                        job(Carrier.ALPHA, ClaimedJob.Action.CREATE, 1, false),
                                        huge)
                                .nextAction())
                .isNull();
    }

    @Test
    void deterministicJitterAndAttemptBound() {
        var decision =
                policy.afterCall(
                        job(Carrier.ALPHA, ClaimedJob.Action.CREATE, 2, false),
                        CarrierOutcome.of(CarrierOutcome.Kind.RETRYABLE, "RATE_LIMIT"));
        assertThat(decision.delay()).isEqualTo(Duration.ofMillis(1_500));
        assertThat(policy.beforeCall(job(Carrier.ALPHA, ClaimedJob.Action.CREATE, 3, false)))
                .isEmpty();
        assertThat(policy.beforeCall(job(Carrier.ALPHA, ClaimedJob.Action.CREATE, 4, false)))
                .isPresent();
    }
}

package com.company.delivery.service;

import com.company.delivery.client.CarrierClient;
import com.company.delivery.client.CarrierOutcome;
import com.company.delivery.config.CarrierProperties;
import com.company.delivery.config.WorkerProperties;
import com.company.delivery.model.Carrier;
import com.company.delivery.model.ClaimedJob;
import com.company.delivery.model.Decision;
import com.company.delivery.repository.JobRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DispatchProcessor {

    private final JobRepository jobs;
    private final RetryPolicy policy;
    private final MeterRegistry metrics;
    private final Map<Carrier, CarrierClient> clients;

    public DispatchProcessor(
            JobRepository jobs,
            List<CarrierClient> clients,
            WorkerProperties worker,
            CarrierProperties http,
            RetryPolicy policy,
            MeterRegistry metrics) {
        this.jobs = jobs;
        this.policy = policy;
        this.metrics = metrics;
        this.clients = indexClients(clients);
        if (worker.lease().compareTo(http.requestTimeout().plus(http.connectTimeout())) <= 0) {
            throw new IllegalArgumentException("Lease must exceed the HTTP timeout budget");
        }
    }

    private static Map<Carrier, CarrierClient> indexClients(List<CarrierClient> clients) {
        Map<Carrier, CarrierClient> indexed = new EnumMap<>(Carrier.class);
        for (CarrierClient client : clients) {
            Carrier carrier = client.carrier();
            if (carrier == null) {
                throw new IllegalArgumentException("Carrier client must declare a carrier");
            }
            if (indexed.putIfAbsent(carrier, client) != null) {
                throw new IllegalArgumentException(
                        "Multiple carrier clients registered for " + carrier);
            }
        }
        var missing = EnumSet.allOf(Carrier.class);
        missing.removeAll(indexed.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing carrier clients: " + missing);
        }
        return Map.copyOf(indexed);
    }

    public boolean runOne(Carrier carrier) {
        var claimed = claim(carrier);
        if (claimed.isEmpty()) {
            return false;
        }
        process(claimed.get());
        return true;
    }

    private Optional<ClaimedJob> claim(Carrier carrier) {
        try {
            return jobs.claim(carrier);
        } catch (RuntimeException error) {
            recordWorkerError(carrier, "NONE", "CLAIM");
            log.error(
                    "Dispatch claim failed carrier={} stage=CLAIM exceptionType={} location={}",
                    carrier,
                    error.getClass().getName(),
                    failureLocation(error));
            return Optional.empty();
        }
    }

    public void process(ClaimedJob job) {
        log.info(
                "Dispatch attempt started bookingId={} attemptId={} carrier={} operation={}"
                        + " attempt={}",
                job.bookingId(),
                job.token(),
                job.carrier(),
                job.action(),
                job.actionAttempt());
        String stage = "CHECK_BUDGET";
        try {
            var expired = policy.beforeCall(job);
            if (expired.isPresent()) {
                stage = "COMPLETE";
                finish(job, expired.get());
                return;
            }
            stage = "CARRIER_CALL";
            CarrierOutcome result = callCarrier(job);
            stage = "DECIDE";
            Decision decision = policy.afterCall(job, result);
            stage = "COMPLETE";
            finish(job, decision);
        } catch (RuntimeException error) {
            // Preserve database state for lease recovery. Exception messages and causes
            // can contain private payloads, so log only type and application frames.
            recordWorkerError(job.carrier(), job.action().name(), stage);
            log.error(
                    "Dispatch attempt failed bookingId={} attemptId={} carrier={} operation={}"
                            + " attempt={} stage={} exceptionType={} location={}",
                    job.bookingId(),
                    job.token(),
                    job.carrier(),
                    job.action(),
                    job.actionAttempt(),
                    stage,
                    error.getClass().getName(),
                    failureLocation(error));
        }
    }

    private CarrierOutcome callCarrier(ClaimedJob job) {
        var timer = Timer.start(metrics);
        String outcome = "ERROR";
        try {
            CarrierClient client = clients.get(job.carrier());
            var result =
                    job.action() == ClaimedJob.Action.CREATE
                            ? client.create(job.bookingId(), job.request())
                            : client.lookup(job.bookingId());
            outcome = result.kind().name();
            return result;
        } finally {
            timer.stop(
                    metrics.timer(
                            "delivery.carrier.duration",
                            "carrier",
                            job.carrier().name(),
                            "operation",
                            job.action().name(),
                            "outcome",
                            outcome));
        }
    }

    private void recordWorkerError(Carrier carrier, String operation, String stage) {
        metrics.counter(
                        "delivery.worker.errors",
                        "carrier", carrier.name(),
                        "operation", operation,
                        "stage", stage)
                .increment();
    }

    private String failureLocation(RuntimeException error) {
        return Arrays.stream(error.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.company.delivery."))
                .limit(5)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining(" | "));
    }

    private void finish(ClaimedJob job, Decision decision) {
        boolean saved = jobs.complete(job, decision);
        metrics.counter(
                        "delivery.attempts",
                        "carrier",
                        job.carrier().name(),
                        "outcome",
                        saved ? decision.status().name() : "STALE")
                .increment();
        if (saved && decision.nextAction() == null) {
            metrics.timer(
                            "delivery.booking.duration",
                            "carrier",
                            job.carrier().name(),
                            "outcome",
                            decision.status().name())
                    .record(Duration.between(job.createdAt(), Instant.now()).abs());
        }
        log.info(
                "Dispatch attempt finished bookingId={} attemptId={} carrier={} outcome={} saved={}"
                        + " nextAction={} retryDelayMs={} code={}",
                job.bookingId(),
                job.token(),
                job.carrier(),
                decision.status(),
                saved,
                decision.nextAction(),
                decision.delay().toMillis(),
                decision.outcome().code());
    }
}

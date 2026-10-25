package com.company.delivery.service;

import static com.company.delivery.support.BookingFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.company.delivery.client.CarrierClient;
import com.company.delivery.client.CarrierOutcome;
import com.company.delivery.config.CarrierProperties;
import com.company.delivery.config.WorkerProperties;
import com.company.delivery.model.BookingStatus;
import com.company.delivery.model.Carrier;
import com.company.delivery.model.ClaimedJob;
import com.company.delivery.model.Decision;
import com.company.delivery.repository.JobRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class DispatchProcessorTest {

    private final JobRepository jobs = mock(JobRepository.class);
    private final RetryPolicy policy = mock(RetryPolicy.class);
    private final CarrierClient alpha = mock(CarrierClient.class);
    private final CarrierClient beta = mock(CarrierClient.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DispatchProcessor.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private DispatchProcessor processor;

    @BeforeEach
    void setup() {
        when(alpha.carrier()).thenReturn(Carrier.ALPHA);
        when(beta.carrier()).thenReturn(Carrier.BETA);
        when(policy.beforeCall(any())).thenReturn(Optional.empty());
        processor = createProcessor(List.of(alpha, beta));
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        logs.stop();
        metrics.close();
    }

    @ParameterizedTest
    @EnumSource(Carrier.class)
    void rejectsMissingCarrierAtConstruction(Carrier missing) {
        var available = List.of(alpha, beta).stream()
                .filter(client -> client.carrier() != missing)
                .toList();

        assertThatThrownBy(() -> createProcessor(available))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing carrier clients: [" + missing + "]");
    }

    @Test
    void rejectsEmptyClientRegistryAtConstruction() {
        assertThatThrownBy(() -> createProcessor(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("Missing carrier clients:", "ALPHA", "BETA");
    }

    @ParameterizedTest
    @EnumSource(Carrier.class)
    void rejectsDuplicateCarrierAtConstruction(Carrier carrier) {
        var duplicate = mock(CarrierClient.class);
        when(duplicate.carrier()).thenReturn(carrier);

        assertThatThrownBy(() -> createProcessor(List.of(alpha, beta, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Multiple carrier clients registered for " + carrier);
    }

    @Test
    void rejectsClientWithoutDeclaredCarrier() {
        var undeclared = mock(CarrierClient.class);

        assertThatThrownBy(() -> createProcessor(List.of(alpha, beta, undeclared)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Carrier client must declare a carrier");
    }

    @ParameterizedTest
    @EnumSource(Carrier.class)
    void dispatchesToTheRegisteredClientRegardlessOfRegistrationOrder(Carrier carrier) {
        processor = createProcessor(List.of(beta, alpha));
        var job = job(ClaimedJob.Action.CREATE, carrier);
        CarrierClient selected = carrier == Carrier.ALPHA ? alpha : beta;
        CarrierClient other = carrier == Carrier.ALPHA ? beta : alpha;
        var result = CarrierOutcome.success("TRACK", "https://labels.example/test");
        var decision = Decision.terminal(BookingStatus.BOOKED, result);
        when(jobs.claim(carrier)).thenReturn(Optional.of(job));
        when(selected.create(job.bookingId(), job.request())).thenReturn(result);
        when(policy.afterCall(job, result)).thenReturn(decision);
        when(jobs.complete(job, decision)).thenReturn(true);

        assertThat(processor.runOne(carrier)).isTrue();

        verify(selected).create(job.bookingId(), job.request());
        verify(other, never()).create(any(), any());
        verify(jobs).complete(job, decision);
    }

    @ParameterizedTest
    @EnumSource(ClaimedJob.Action.class)
    void unexpectedCarrierErrorRecordsDurationAndSafeDiagnostics(ClaimedJob.Action action) {
        var job = job(action);
        var error = privateFailure();
        when(jobs.claim(Carrier.ALPHA)).thenReturn(Optional.of(job));
        when(alpha.create(job.bookingId(), job.request())).thenThrow(error);
        when(alpha.lookup(job.bookingId())).thenThrow(error);

        assertThat(processor.runOne(Carrier.ALPHA)).isTrue();

        verify(jobs, never()).complete(any(), any());
        assertWorkerError(action.name(), "CARRIER_CALL");
        assertThat(metrics.get("delivery.carrier.duration")
                        .tags("carrier", "ALPHA", "operation", action.name(), "outcome", "ERROR")
                        .timer().count())
                .isEqualTo(1);
        assertFailureLogged(job, "CARRIER_CALL");
    }

    @Test
    void finalizationErrorKeepsSuccessfulCarrierTimingAndRecordsWorkerFailure() {
        var job = job(ClaimedJob.Action.CREATE);
        var result = CarrierOutcome.success("PRIVATE-TRACKING", "https://labels.example/private");
        var decision = Decision.terminal(BookingStatus.BOOKED, result);
        when(jobs.claim(Carrier.ALPHA)).thenReturn(Optional.of(job));
        when(alpha.create(job.bookingId(), job.request())).thenReturn(result);
        when(policy.afterCall(job, result)).thenReturn(decision);
        when(jobs.complete(job, decision)).thenThrow(privateFailure());

        assertThat(processor.runOne(Carrier.ALPHA)).isTrue();

        verify(jobs).complete(job, decision);
        assertWorkerError("CREATE", "COMPLETE");
        assertThat(metrics.get("delivery.carrier.duration")
                        .tags("carrier", "ALPHA", "operation", "CREATE", "outcome", "SUCCESS")
                        .timer().count())
                .isEqualTo(1);
        assertThat(metrics.find("delivery.carrier.duration").tag("outcome", "ERROR").timer())
                .isNull();
        assertFailureLogged(job, "COMPLETE");
    }

    @Test
    void budgetCheckErrorDoesNotInvokeCarrierOrFinalizeTheJob() {
        var job = job(ClaimedJob.Action.CREATE);
        when(jobs.claim(Carrier.ALPHA)).thenReturn(Optional.of(job));
        when(policy.beforeCall(job)).thenThrow(privateFailure());

        assertThat(processor.runOne(Carrier.ALPHA)).isTrue();

        verify(alpha, never()).create(any(), any());
        verify(jobs, never()).complete(any(), any());
        assertThat(metrics.find("delivery.carrier.duration").timers()).isEmpty();
        assertWorkerError("CREATE", "CHECK_BUDGET");
        assertFailureLogged(job, "CHECK_BUDGET");
    }

    @Test
    void claimErrorIsRecordedWithoutInventingBookingIdentifiers() {
        when(jobs.claim(Carrier.ALPHA)).thenThrow(privateFailure());

        assertThat(processor.runOne(Carrier.ALPHA)).isFalse();

        verify(jobs, never()).complete(any(), any());
        assertWorkerError("NONE", "CLAIM");
        var event = errorLog();
        assertThat(event.getFormattedMessage())
                .contains("Dispatch claim failed", "carrier=ALPHA", "stage=CLAIM", "location=")
                .doesNotContain("bookingId=", "attemptId=");
        assertPrivateDataExcluded(event);
    }

    private ClaimedJob job(ClaimedJob.Action action) {
        return job(action, Carrier.ALPHA);
    }

    private ClaimedJob job(ClaimedJob.Action action, Carrier carrier) {
        var now = Instant.now();
        return new ClaimedJob(
                UUID.randomUUID(), UUID.randomUUID(), action, carrier,
                request(1_000, "PL"), now, now, 1, action == ClaimedJob.Action.LOOKUP);
    }

    private DispatchProcessor createProcessor(List<CarrierClient> clients) {
        return new DispatchProcessor(
                jobs,
                clients,
                new WorkerProperties(
                        2, Duration.ofSeconds(30), 5, Duration.ofHours(1),
                        Duration.ofSeconds(1), Duration.ofSeconds(30)),
                new CarrierProperties(
                        URI.create("http://localhost:9091"), URI.create("http://localhost:9092"),
                        Duration.ofSeconds(2), Duration.ofSeconds(5)),
                policy,
                metrics);
    }

    private RuntimeException privateFailure() {
        return new IllegalStateException(
                "PRIVATE-ADDRESS PRIVATE-KEY", new RuntimeException("PRIVATE-CAUSE"));
    }

    private void assertWorkerError(String operation, String stage) {
        assertThat(metrics.get("delivery.worker.errors")
                        .tags("carrier", "ALPHA", "operation", operation, "stage", stage)
                        .counter().count())
                .isEqualTo(1);
        assertThat(metrics.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getKey())
                                .isNotIn("bookingId", "attemptId", "exceptionType")));
    }

    private void assertFailureLogged(ClaimedJob job, String stage) {
        var event = errorLog();
        assertThat(event.getFormattedMessage())
                .contains(
                        "bookingId=" + job.bookingId(), "attemptId=" + job.token(),
                        "carrier=ALPHA", "operation=" + job.action(), "stage=" + stage,
                        "exceptionType=java.lang.IllegalStateException",
                        "location=com.company.delivery.",
                        "com.company.delivery.service.DispatchProcessor.");
        assertPrivateDataExcluded(event);
    }

    private ILoggingEvent errorLog() {
        var errors = logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }

    private void assertPrivateDataExcluded(ILoggingEvent event) {
        assertThat(event.getFormattedMessage())
                .doesNotContain("PRIVATE-ADDRESS", "PRIVATE-KEY", "PRIVATE-CAUSE", "PRIVATE-TRACKING",
                        "https://labels.example/private", "Example 1", "Example 2");
        assertThat(event.getThrowableProxy()).isNull();
    }
}

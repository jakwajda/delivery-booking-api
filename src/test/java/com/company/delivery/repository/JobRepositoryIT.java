package com.company.delivery.repository;

import static com.company.delivery.support.BookingFixtures.request;

import static org.assertj.core.api.Assertions.*;

import com.company.delivery.DatabaseTest;
import com.company.delivery.client.CarrierOutcome;
import com.company.delivery.model.BookingStatus;
import com.company.delivery.model.Carrier;
import com.company.delivery.model.ClaimedJob;
import com.company.delivery.model.Decision;
import com.company.delivery.service.BookingService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.*;
import java.util.stream.IntStream;

class JobRepositoryIT extends DatabaseTest {

    @Autowired
    private BookingService bookings;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE booking CASCADE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{invalid", "null", "{}", "{\"serviceType\":\"SAME_DAY\"}"})
    void invalidStoredPayloadDoesNotBlockTheNextJob(String payload) {
        var invalid = bookings.submit("invalid", request(1_000, "PL"));
        var valid = bookings.submit("valid", request(1_000, "PL"));
        jdbc.update("UPDATE booking SET payload = ? WHERE id = ?", payload, invalid.id());
        jdbc.update(
                "UPDATE dispatch_job SET next_attempt_at = clock_timestamp() - interval '1 hour'"
                        + " WHERE booking_id = ?",
                invalid.id());

        assertThat(jobs.claim(Carrier.ALPHA)).isEmpty();

        var rejected = bookings.get(invalid.id());
        assertThat(rejected.status()).isEqualTo(BookingStatus.FAILED);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_STORED_PAYLOAD");
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM dispatch_job WHERE booking_id = ?",
                        String.class,
                        invalid.id()))
                .isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch_attempt WHERE booking_id = ?",
                        Integer.class,
                        invalid.id()))
                .isZero();

        var next = jobs.claim(Carrier.ALPHA).orElseThrow();
        assertThat(next.bookingId()).isEqualTo(valid.id());
        assertThat(jobs.complete(
                        next,
                        Decision.terminal(
                                BookingStatus.BOOKED,
                                CarrierOutcome.success("TRACK", "https://labels.example/valid"))))
                .isTrue();
        assertThat(bookings.get(valid.id()).status()).isEqualTo(BookingStatus.BOOKED);
        assertThat(jobs.claim(Carrier.ALPHA)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidStoredPayloadPreservesUncertaintyAndRejectsTheOldOwner(boolean expiredLease) {
        var booking = bookings.submit("uncertain", request(1_000, "PL"));
        var previous = jobs.claim(Carrier.ALPHA).orElseThrow();
        if (expiredLease) {
            jdbc.update(
                    "UPDATE dispatch_job SET lease_until = clock_timestamp() - interval '1 second'"
                            + " WHERE booking_id = ?",
                    booking.id());
        } else {
            assertThat(jobs.complete(
                            previous,
                            new Decision(
                                    BookingStatus.RETRY_SCHEDULED,
                                    ClaimedJob.Action.CREATE,
                                    java.time.Duration.ZERO,
                                    CarrierOutcome.of(CarrierOutcome.Kind.UNKNOWN, "TIMEOUT"))))
                    .isTrue();
        }
        jdbc.update("UPDATE booking SET payload = '{invalid' WHERE id = ?", booking.id());

        assertThat(jobs.claim(Carrier.ALPHA)).isEmpty();

        assertThat(bookings.get(booking.id()).status())
                .isEqualTo(BookingStatus.REQUIRES_RECONCILIATION);
        assertThat(bookings.get(booking.id()).errorCode()).isEqualTo("INVALID_STORED_PAYLOAD");
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM dispatch_job WHERE booking_id = ?",
                        String.class,
                        booking.id()))
                .isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                        "SELECT uncertain FROM dispatch_job WHERE booking_id = ?",
                        Boolean.class,
                        booking.id()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch_attempt WHERE booking_id = ? AND finished_at IS NULL",
                        Integer.class,
                        booking.id()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT lease_token FROM dispatch_job WHERE booking_id = ?",
                        java.util.UUID.class,
                        booking.id()))
                .isNull();
        assertThat(jobs.complete(
                        previous,
                        Decision.terminal(
                                BookingStatus.BOOKED,
                                CarrierOutcome.success("LATE", "https://labels.example/late"))))
                .isFalse();
    }

    @Test
    void concurrentWorkersClaimDifferentJobs() throws Exception {
        IntStream.range(0, 8).forEach(i -> bookings.submit("job-" + i, request(1, "PL")));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures =
                    IntStream.range(0, 8)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        return jobs.claim(Carrier.ALPHA)
                                                                .orElseThrow()
                                                                .bookingId();
                                                    }))
                            .toList();
            start.countDown();
            var ids = new java.util.HashSet<java.util.UUID>();
            for (var future : futures) {
                ids.add(future.get(15, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(8);
        }
        assertThat(jobs.claim(Carrier.ALPHA)).isEmpty();
    }

    @Test
    void expiredLeaseChangesTokenAndStaleWorkerCannotSave() {
        var booking = bookings.submit("job", request(1, "PL"));
        var first = jobs.claim(Carrier.ALPHA).orElseThrow();
        jdbc.update(
                "UPDATE dispatch_job SET lease_until = clock_timestamp() - interval '1 second'");
        var second = jobs.claim(Carrier.ALPHA).orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.action()).isEqualTo(ClaimedJob.Action.LOOKUP);
        var success =
                Decision.terminal(
                        BookingStatus.BOOKED,
                        CarrierOutcome.success("TRACK", "https://labels.example/a"));
        assertThat(jobs.complete(first, success)).isFalse();
        assertThat(bookings.get(booking.id()).status()).isEqualTo(BookingStatus.RECONCILING);
        assertThat(jobs.complete(second, success)).isTrue();
        assertThat(bookings.result(booking.id()).trackingNumber()).isEqualTo("TRACK");
        assertThat(jobs.complete(second, success)).isFalse();
    }

    @Test
    void resultAndJobCompletionRollbackTogether() {
        var booking = bookings.submit("rollback", request(1, "PL"));
        var claimed = jobs.claim(Carrier.ALPHA).orElseThrow();
        // A constraint failure in booking must preserve the lease and unfinished attempt.
        assertThatThrownBy(
                        () ->
                                jobs.complete(
                                        claimed,
                                        Decision.terminal(
                                                BookingStatus.BOOKED,
                                                CarrierOutcome.of(
                                                        CarrierOutcome.Kind.UNKNOWN,
                                                        "missing-result"))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(bookings.get(booking.id()).status()).isEqualTo(BookingStatus.PROCESSING);
        assertThat(jdbc.queryForObject("select state from dispatch_job", String.class))
                .isEqualTo("RUNNING");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from dispatch_attempt where finished_at is null",
                                Integer.class))
                .isEqualTo(1);
    }
}

package com.company.delivery.service;

import static com.company.delivery.support.BookingFixtures.request;

import static org.assertj.core.api.Assertions.*;

import com.company.delivery.DatabaseTest;
import com.company.delivery.client.AlphaClient;
import com.company.delivery.client.CarrierOutcome;
import com.company.delivery.model.BookingStatus;
import com.company.delivery.model.Carrier;
import com.company.delivery.model.Decision;
import com.company.delivery.repository.JobRepository;
import com.company.delivery.support.CarrierStub;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class RecoveryIT extends DatabaseTest {

    private static final CarrierStub STUB = new CarrierStub();

    @DynamicPropertySource
    static void carriers(DynamicPropertyRegistry properties) {
        properties.add("delivery.carriers.alpha-url", () -> STUB.url().toString());
        properties.add("delivery.carriers.beta-url", () -> STUB.url().toString());
        properties.add("delivery.carriers.request-timeout", () -> "1s");
    }

    @Autowired
    private BookingService bookings;

    @Autowired
    private DispatchProcessor processor;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AlphaClient alpha;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE booking CASCADE");
        STUB.reset();
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    void ready() {
        jdbc.update("UPDATE dispatch_job SET next_attempt_at = clock_timestamp()");
    }

    @Test
    void alphaLostResponseCreatesOneShipment() {
        var booking = bookings.submit("alpha", request(1_000, "PL"));
        STUB.loseNextResponse.set(true);
        processor.runOne(Carrier.ALPHA);
        assertThat(bookings.get(booking.id()).status()).isEqualTo(BookingStatus.RETRY_SCHEDULED);
        ready();
        processor.runOne(Carrier.ALPHA);
        assertThat(bookings.get(booking.id()).status()).isEqualTo(BookingStatus.BOOKED);
        assertThat(STUB.creations.get()).isEqualTo(1);
    }

    @Test
    void betaUsesLookupWhileVisibilityLags() {
        var booking = bookings.submit("beta", request(1_000, "DE"));
        STUB.loseNextResponse.set(true);
        processor.runOne(Carrier.BETA);
        STUB.hiddenLookups.set(2);
        for (int i = 0; i < 3; i++) {
            ready();
            processor.runOne(Carrier.BETA);
        }
        assertThat(bookings.get(booking.id()).status()).isEqualTo(BookingStatus.BOOKED);
        assertThat(STUB.createCalls.get()).isEqualTo(1);
    }

    @Test
    void permanentlyMissingBetaRequiresOperator() {
        var booking = bookings.submit("beta-missing", request(1_000, "DE"));
        STUB.loseNextResponse.set(true);
        processor.runOne(Carrier.BETA);
        STUB.hiddenLookups.set(100);
        for (int i = 0; i < 5; i++) {
            ready();
            processor.runOne(Carrier.BETA);
        }
        assertThat(bookings.get(booking.id()).status())
                .isEqualTo(BookingStatus.REQUIRES_RECONCILIATION);
        assertThat(STUB.createCalls.get()).isEqualTo(1);
    }

    @Test
    void crashAfterExternalSuccessBeforeCommitRecoversByLookup() {
        var booking = bookings.submit("crash", request(1_000, "PL"));
        var claimed = jobs.claim(Carrier.ALPHA).orElseThrow();
        assertThat(alpha.create(claimed.bookingId(), claimed.request()).kind())
                .isEqualTo(CarrierOutcome.Kind.SUCCESS);
        // Simulate process loss: no finalize; another worker sees an expired lease.
        jdbc.update(
                "UPDATE dispatch_job SET lease_until = clock_timestamp() - interval '1 second'");
        processor.runOne(Carrier.ALPHA);
        assertThat(bookings.get(booking.id()).status()).isEqualTo(BookingStatus.BOOKED);
        assertThat(STUB.createCalls.get()).isEqualTo(1);
        assertThat(
                        jobs.complete(
                                claimed,
                                Decision.terminal(
                                        BookingStatus.REJECTED,
                                        CarrierOutcome.of(CarrierOutcome.Kind.REJECTED, "LATE"))))
                .isFalse();
    }

    @Test
    void exceededAgeStopsBeforeCallingProvider() {
        var booking = bookings.submit("old", request(1_000, "PL"));
        jdbc.update("UPDATE booking SET created_at = clock_timestamp() - interval '2 hours'");
        processor.runOne(Carrier.ALPHA);
        assertThat(bookings.get(booking.id()).status()).isEqualTo(BookingStatus.FAILED);
        assertThat(STUB.createCalls.get()).isZero();
    }
}

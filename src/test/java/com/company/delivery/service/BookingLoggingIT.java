package com.company.delivery.service;

import static com.company.delivery.support.BookingFixtures.request;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.company.delivery.DatabaseTest;
import com.company.delivery.exception.BookingProblem;

import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class BookingLoggingIT extends DatabaseTest {

    @Autowired
    private BookingService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;
    private final Logger logger = (Logger) LoggerFactory.getLogger(BookingService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void capture() {
        jdbc.execute("TRUNCATE booking CASCADE");
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void recordsAcceptanceReplayAndConflictWithoutSensitiveData() {
        var booking = service.submit("private-key", request(1_000, "PL"));
        service.submit("private-key", request(1_000, "PL"));
        assertThatThrownBy(() -> service.submit("private-key", request(2_000, "PL")))
                .isInstanceOf(BookingProblem.class);
        assertThat(logs.list)
                .extracting(ILoggingEvent::getMessage)
                .containsExactly(
                        "Booking accepted and queued bookingId={} carrier={} status={}",
                        "Existing booking returned for repeated request bookingId={} status={}",
                        "Idempotency conflict detected bookingId={}");
        assertThat(logs.list.getFirst().getFormattedMessage()).contains(booking.id().toString());
        for (var event : logs.list) {
            assertThat(event.getFormattedMessage())
                    .doesNotContain("private-key", "Example 1", "Example 2", "order-1");
        }
    }

    @Test
    void neverAnnouncesAcceptanceBeforeCommitOrAfterRollback() {
        transactions.executeWithoutResult(
                tx -> {
                    service.submit("rollback", request(1_000, "PL"));
                    assertThat(logs.list).isEmpty();
                    tx.setRollbackOnly();
                });
        assertThat(logs.list).isEmpty();
    }

    @Test
    void includesBookingIdWhenRetrievingConfirmedResult() {
        var booking = service.submit("result-log", request(1_000, "PL"));
        jdbc.update(
                """
                UPDATE booking SET status='BOOKED', tracking_number='PRIVATE-TRACKING',
                    label_url='https://labels.example/private-label' WHERE id=?
                """,
                booking.id());
        logs.list.clear();
        service.result(booking.id());
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
                .contains(
                        "Confirmed booking result retrieved",
                        "bookingId=" + booking.id(),
                        "carrier=ALPHA")
                .doesNotContain("PRIVATE-TRACKING", "private-label");
    }
}

package com.company.delivery.metrics;

import static com.company.delivery.support.BookingFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.delivery.DatabaseTest;
import com.company.delivery.service.BookingService;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

class QueueMetricsIT extends DatabaseTest {

    @Autowired
    private BookingService bookings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry metrics;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE booking CASCADE");
    }

    @Test
    void separatesDueJobsFromScheduledJobsAndExcludesOtherStates() {
        createJob("due", "READY", -3_600);
        createJob("due-now", "READY", 0);
        createJob("scheduled", "READY", 3_600);
        createJob("scheduled-later", "READY", 7_200);
        createJob("running", "RUNNING", -3_600);
        createJob("done", "DONE", 3_600);

        assertThat(gauge("delivery.queue.ready")).isEqualTo(2);
        assertThat(gauge("delivery.queue.scheduled")).isEqualTo(2);
    }

    @Test
    void gaugesReflectReschedulingWithoutRestartingTheApplication() {
        assertThat(gauge("delivery.queue.ready")).isZero();
        assertThat(gauge("delivery.queue.scheduled")).isZero();
        UUID id = createJob("rescheduled", "READY", 3_600);
        assertThat(gauge("delivery.queue.ready")).isZero();
        assertThat(gauge("delivery.queue.scheduled")).isEqualTo(1);

        reschedule(id, -3_600);
        assertThat(gauge("delivery.queue.ready")).isEqualTo(1);
        assertThat(gauge("delivery.queue.scheduled")).isZero();

        reschedule(id, 3_600);
        assertThat(gauge("delivery.queue.ready")).isZero();
        assertThat(gauge("delivery.queue.scheduled")).isEqualTo(1);
    }

    private UUID createJob(String key, String state, int delaySeconds) {
        UUID id = bookings.submit(key, request(1_000, "PL")).id();
        jdbc.update("UPDATE dispatch_job SET state = ? WHERE booking_id = ?", state, id);
        reschedule(id, delaySeconds);
        return id;
    }

    private void reschedule(UUID id, int delaySeconds) {
        jdbc.update(
                """
                UPDATE dispatch_job
                SET next_attempt_at = clock_timestamp() + (? * interval '1 second')
                WHERE booking_id = ?
                """,
                delaySeconds,
                id);
    }

    private double gauge(String name) {
        return metrics.get(name).gauge().value();
    }
}

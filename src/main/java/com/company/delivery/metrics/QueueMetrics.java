package com.company.delivery.metrics;

import io.micrometer.core.instrument.*;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class QueueMetrics {

    private final JdbcTemplate jdbc;

    public QueueMetrics(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        gauge(
                metrics,
                "delivery.queue.ready",
                """
                SELECT count(*) FROM dispatch_job
                WHERE state='READY' AND next_attempt_at <= clock_timestamp()
                """);
        gauge(
                metrics,
                "delivery.queue.scheduled",
                """
                SELECT count(*) FROM dispatch_job
                WHERE state='READY' AND next_attempt_at > clock_timestamp()
                """);
        gauge(
                metrics,
                "delivery.queue.oldest.seconds",
                """
                SELECT coalesce(extract(epoch FROM clock_timestamp() - min(b.created_at)), 0)
                FROM booking b JOIN dispatch_job j ON j.booking_id=b.id WHERE j.state <> 'DONE'
                """);
        gauge(
                metrics,
                "delivery.queue.expired.leases",
                """
                SELECT count(*) FROM dispatch_job WHERE state='RUNNING' AND lease_until < clock_timestamp()
                """);
        gauge(
                metrics,
                "delivery.queue.manual",
                "SELECT count(*) FROM booking WHERE status='REQUIRES_RECONCILIATION'");
    }

    private void gauge(MeterRegistry metrics, String name, String sql) {
        Gauge.builder(name, this, ignored -> read(sql)).register(metrics);
    }

    private double read(String sql) {
        try {
            return jdbc.queryForObject(sql, Double.class);
        } catch (DataAccessException unavailable) {
            return Double.NaN;
        }
    }
}

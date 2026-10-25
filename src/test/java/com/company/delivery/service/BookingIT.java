package com.company.delivery.service;

import static com.company.delivery.support.BookingFixtures.request;

import static org.assertj.core.api.Assertions.*;

import com.company.delivery.DatabaseTest;
import com.company.delivery.dto.BookingRequest.ServiceType;
import com.company.delivery.dto.BookingView;
import com.company.delivery.exception.BookingProblem;
import com.company.delivery.model.BookingStatus;
import com.company.delivery.model.Carrier;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;
import java.util.stream.IntStream;

class BookingIT extends DatabaseTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private BookingService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE booking CASCADE");
    }

    @Test
    void acceptsReplaysAndRejectsChangedPayload() {
        var first = service.submit("key", request(1_000, "PL"));
        assertThat(service.submit("key", request(1_000, "PL")).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> service.submit("key", request(2_000, "PL")))
                .isInstanceOf(BookingProblem.class)
                .hasMessageContaining("different data");
        assertThat(jdbc.queryForObject("select count(*) from dispatch_job", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> service.result(first.id())).isInstanceOf(BookingProblem.class);
    }

    @Test
    void acceptsExpressOverHttpAndRejectsServiceTypeChangeOnReplay() {
        var http = org.springframework.web.client.RestClient.create("http://localhost:" + port);
        var express = request(1_000, "PL", ServiceType.EXPRESS);
        var response =
                http.post()
                        .uri("/api/v1/bookings")
                        .header("Idempotency-Key", "express-http")
                        .body(express)
                        .retrieve()
                        .toEntity(BookingView.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(service.submit("express-http", express).id()).isEqualTo(response.getBody().id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload FROM booking WHERE id = ?",
                                String.class,
                                response.getBody().id()))
                .contains("\"serviceType\":\"EXPRESS\"");
        int conflict =
                http.post()
                        .uri("/api/v1/bookings")
                        .header("Idempotency-Key", "express-http")
                        .body(request(1_000, "PL"))
                        .exchange((req, res) -> res.getStatusCode().value());
        assertThat(conflict).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_job", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentRequestsHaveOneBookingAndJob() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures =
                    IntStream.range(0, 8)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        return service.submit(
                                                                        "same-key",
                                                                        request(1_000, "PL"))
                                                                .id();
                                                    }))
                            .toList();
            start.countDown();
            var ids = new java.util.HashSet<java.util.UUID>();
            for (var future : futures) {
                ids.add(future.get(15, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from booking", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from dispatch_job", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rollbackDoesNotLeaveAnOrphan() {
        transactions.executeWithoutResult(
                tx -> {
                    service.submit("rollback", request(1_000, "PL"));
                    tx.setRollbackOnly();
                });
        assertThat(jdbc.queryForObject("select count(*) from booking", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from dispatch_job", Integer.class))
                .isZero();
    }

    @Test
    void selectsBetaAndRejectsUnsupportedRoute() {
        assertThat(service.submit("beta", request(11_000, "PL")).carrier()).isEqualTo(Carrier.BETA);
        assertThatThrownBy(() -> service.submit("unsupported", request(1_000, "US")))
                .isInstanceOf(BookingProblem.class);
    }

    @Test
    void httpContractAcceptsValidRequestsAndHidesInvalidFieldValues() {
        var http = org.springframework.web.client.RestClient.create("http://localhost:" + port);
        var response =
                http.post()
                        .uri("/api/v1/bookings")
                        .header("Idempotency-Key", "http")
                        .body(request(1_000, "PL"))
                        .retrieve()
                        .toEntity(BookingView.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/v1/bookings/" + response.getBody().id());
        assertThat(
                        http.get()
                                .uri(response.getHeaders().getLocation().toString())
                                .retrieve()
                                .body(BookingView.class)
                                .status())
                .isEqualTo(BookingStatus.ACCEPTED);
        int invalidStatus =
                http.post()
                        .uri("/api/v1/bookings")
                        .header("Idempotency-Key", "invalid")
                        .body(request(0, "PL"))
                        .exchange((req, res) -> res.getStatusCode().value());
        assertThat(invalidStatus).isEqualTo(400);
    }

    @Test
    void observedMethodsRecordCallsAndExceptions() {
        long submittedBefore = observationCount("delivery.booking.submit");
        long statusBefore = observationCount("delivery.booking.status");
        long resultBefore = observationCount("delivery.booking.result");
        long resultErrorsBefore = observationErrors("delivery.booking.result");

        var booking = service.submit("observed", request(1_000, "PL"));
        service.get(booking.id());
        assertThatThrownBy(() -> service.result(booking.id())).isInstanceOf(BookingProblem.class);

        assertThat(observationCount("delivery.booking.submit")).isEqualTo(submittedBefore + 1);
        assertThat(observationCount("delivery.booking.status")).isEqualTo(statusBefore + 1);
        assertThat(observationCount("delivery.booking.result")).isEqualTo(resultBefore + 1);
        assertThat(observationErrors("delivery.booking.result")).isEqualTo(resultErrorsBefore + 1);
        assertThat(meterRegistry.get("delivery.booking.submit").timers())
                .allSatisfy(timer -> assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isPositive());
    }

    private long observationCount(String name) {
        return meterRegistry.find(name).timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    private long observationErrors(String name) {
        return meterRegistry.find(name).timers().stream()
                .filter(timer -> "BookingProblem".equals(timer.getId().getTag("error")))
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    @Test
    void exposesReadinessAndLowCardinalityMetrics() {
        service.submit("metrics", request(1_000, "PL"));
        var http = org.springframework.web.client.RestClient.create("http://localhost:" + port);
        assertThat(http.get().uri("/actuator/health/readiness").retrieve().body(String.class))
                .contains("UP");
        var metrics = http.get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(metrics).contains("delivery_bookings_accepted_total", "delivery_queue_ready",
                "delivery_queue_scheduled",
                "delivery_booking_submit_seconds_count", "delivery_booking_submit_seconds_sum");
        assertThat(metrics).doesNotContain("orderReference=", "bookingId=");
    }
}

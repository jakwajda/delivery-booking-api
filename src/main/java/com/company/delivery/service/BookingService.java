package com.company.delivery.service;

import com.company.delivery.dto.BookingRequest;
import com.company.delivery.dto.BookingView;
import com.company.delivery.exception.BookingProblem;
import com.company.delivery.model.Booking;
import com.company.delivery.model.BookingStatus;
import com.company.delivery.model.Carrier;
import com.company.delivery.repository.BookingRepository;
import com.company.delivery.repository.BookingWriteRepository;

import io.micrometer.observation.annotation.Observed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookings;
    private final BookingWriteRepository bookingWrites;
    private final CarrierSelector selector;
    private final ObjectMapper json;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    @Observed(name = "delivery.booking.submit", contextualName = "submit-booking")
    @Transactional
    public BookingView submit(String key, BookingRequest request) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BookingProblem(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_KEY",
                    "Idempotency-Key must have 1–128 characters");
        }
        String payload = json.writeValueAsString(request);
        String fingerprint = hash(payload);
        // Check replay before routing: accepted requests retain their original routing decision.
        var existing = bookings.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        Carrier carrier = selector.select(request);
        UUID id = UUID.randomUUID();
        boolean inserted =
                bookingWrites.insertWithJobIfAbsent(id, key, fingerprint, payload, carrier);
        if (inserted) {
            afterCommit(
                    () -> {
                        metrics.counter("delivery.bookings.accepted").increment();
                        log.info(
                                "Booking accepted and queued bookingId={} carrier={} status={}",
                                id,
                                carrier,
                                BookingStatus.ACCEPTED);
                    });
        }
        Booking booking = bookings.findByIdempotencyKey(key).orElseThrow();
        return inserted ? BookingView.from(booking) : replay(booking, fingerprint);
    }

    @Observed(name = "delivery.booking.status", contextualName = "get-booking-status")
    @Transactional(readOnly = true)
    public BookingView get(UUID id) {
        Booking booking = find(id);
        log.info("Booking status retrieved bookingId={} status={}", id, booking.getStatus());
        return BookingView.from(booking);
    }

    @Observed(name = "delivery.booking.result", contextualName = "get-booking-result")
    @Transactional(readOnly = true)
    public BookingView.Result result(UUID id) {
        Booking booking = find(id);
        if (booking.getStatus() != BookingStatus.BOOKED) {
            throw new BookingProblem(
                    HttpStatus.CONFLICT, "RESULT_NOT_READY", "Booking has no confirmed result");
        }
        log.info(
                "Confirmed booking result retrieved bookingId={} carrier={}",
                id,
                booking.getCarrier());
        return new BookingView.Result(
                booking.getCarrier(), booking.getTrackingNumber(), booking.getLabelUrl());
    }

    private Booking find(UUID id) {
        return bookings.findById(id)
                .orElseThrow(
                        () ->
                                new BookingProblem(
                                        HttpStatus.NOT_FOUND,
                                        "BOOKING_NOT_FOUND",
                                        "Booking does not exist"));
    }

    private BookingView replay(Booking booking, String fingerprint) {
        if (!booking.getFingerprint().equals(fingerprint)) {
            log.info("Idempotency conflict detected bookingId={}", booking.getId());
            throw new BookingProblem(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Key was already used with different data");
        }
        afterCommit(
                () ->
                        log.info(
                                "Existing booking returned for repeated request bookingId={}"
                                        + " status={}",
                                booking.getId(),
                                booking.getStatus()));
        return BookingView.from(booking);
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }
}

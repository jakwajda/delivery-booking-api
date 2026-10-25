package com.company.delivery.model;

import com.company.delivery.dto.BookingRequest;

import java.time.Instant;
import java.util.UUID;

public record ClaimedJob(
        UUID bookingId,
        UUID token,
        Action action,
        Carrier carrier,
        BookingRequest request,
        Instant createdAt,
        Instant claimedAt,
        int actionAttempt,
        boolean uncertain) {
    public enum Action {
        CREATE,
        LOOKUP
    }
}

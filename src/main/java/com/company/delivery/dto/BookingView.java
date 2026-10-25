package com.company.delivery.dto;

import com.company.delivery.model.Booking;
import com.company.delivery.model.BookingStatus;
import com.company.delivery.model.Carrier;

import java.time.Instant;
import java.util.UUID;

public record BookingView(
        UUID id,
        Carrier carrier,
        BookingStatus status,
        String errorCode,
        Instant createdAt,
        Instant updatedAt) {
    public static BookingView from(Booking booking) {
        return new BookingView(
                booking.getId(),
                booking.getCarrier(),
                booking.getStatus(),
                booking.getErrorCode(),
                booking.getCreatedAt(),
                booking.getUpdatedAt());
    }

    public record Result(Carrier carrier, String trackingNumber, String labelUrl) {}
}

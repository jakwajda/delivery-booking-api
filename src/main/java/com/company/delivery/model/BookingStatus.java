package com.company.delivery.model;

public enum BookingStatus {
    ACCEPTED,
    PROCESSING,
    RETRY_SCHEDULED,
    RECONCILING,
    BOOKED,
    REJECTED,
    FAILED,
    REQUIRES_RECONCILIATION
}

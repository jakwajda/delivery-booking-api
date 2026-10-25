package com.company.delivery.model;

import com.company.delivery.client.CarrierOutcome;

import java.time.Duration;

public record Decision(
        BookingStatus status,
        ClaimedJob.Action nextAction,
        Duration delay,
        CarrierOutcome outcome) {
    public static Decision terminal(BookingStatus status, CarrierOutcome outcome) {
        return new Decision(status, null, Duration.ZERO, outcome);
    }
}

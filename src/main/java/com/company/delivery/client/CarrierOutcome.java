package com.company.delivery.client;

import java.time.Duration;

public record CarrierOutcome(
        Kind kind, String trackingNumber, String labelUrl, String code, Duration retryAfter) {
    public enum Kind {
        SUCCESS,
        REJECTED,
        RETRYABLE,
        UNKNOWN,
        NOT_FOUND,
        CONFIG_ERROR
    }

    public static CarrierOutcome of(Kind kind, String code) {
        return new CarrierOutcome(kind, null, null, code, Duration.ZERO);
    }

    public static CarrierOutcome success(String tracking, String label) {
        if (tracking == null || tracking.isBlank() || tracking.length() > 128 || label == null) {
            return of(Kind.UNKNOWN, "INVALID_RESPONSE");
        }
        try {
            var uri = java.net.URI.create(label);
            if (uri.getHost() == null
                    || !java.util.Set.of("https", "http").contains(uri.getScheme())) {
                return of(Kind.UNKNOWN, "INVALID_RESPONSE");
            }
        } catch (IllegalArgumentException exception) {
            return of(Kind.UNKNOWN, "INVALID_RESPONSE");
        }
        return new CarrierOutcome(Kind.SUCCESS, tracking, label, null, Duration.ZERO);
    }
}

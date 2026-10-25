package com.company.delivery.model;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {

    @Id
    private UUID id;
    private String idempotencyKey;
    private String fingerprint;

    @Column(columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    private Carrier carrier;

    private int routingVersion;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    private String trackingNumber;

    @Column(columnDefinition = "text")
    private String labelUrl;

    private String errorCode;
    private Instant createdAt;
    private Instant updatedAt;
}

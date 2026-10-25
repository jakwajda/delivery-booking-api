package com.company.delivery.client;

import com.company.delivery.dto.BookingRequest;
import com.company.delivery.model.Carrier;

import java.util.UUID;

public interface CarrierClient {
    Carrier carrier();

    CarrierOutcome create(UUID reference, BookingRequest request);

    CarrierOutcome lookup(UUID reference);
}

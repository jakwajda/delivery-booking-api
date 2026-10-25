package com.company.delivery.service;

import com.company.delivery.dto.BookingRequest;
import com.company.delivery.exception.BookingProblem;
import com.company.delivery.model.Carrier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CarrierSelector {

    public Carrier select(BookingRequest request) {
        if (request.weightGrams() < 1 || !"PL".equals(request.sender().country())) {
            throw unsupported();
        }
        String destination = request.recipient().country();
        if ("PL".equals(destination) && request.weightGrams() <= 10_000) {
            return Carrier.ALPHA;
        }
        if (("PL".equals(destination) || "DE".equals(destination))
                && request.weightGrams() <= 30_000) {
            return Carrier.BETA;
        }
        throw unsupported();
    }

    private BookingProblem unsupported() {
        return new BookingProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "UNSUPPORTED_ROUTE",
                "No carrier supports this shipment");
    }
}

package com.company.delivery.support;

import com.company.delivery.dto.BookingRequest;

public final class BookingFixtures {

    private BookingFixtures() {}

    public static BookingRequest request(int grams, String country) {
        return request(grams, country, BookingRequest.ServiceType.STANDARD);
    }

    public static BookingRequest request(
            int grams, String country, BookingRequest.ServiceType serviceType) {
        var sender = new BookingRequest.Address("PL", "Warsaw", "00-001", "Example 1");
        return new BookingRequest(
                "order-1",
                sender,
                new BookingRequest.Address(country, "City", "00-001", "Example 2"),
                grams,
                serviceType);
    }
}

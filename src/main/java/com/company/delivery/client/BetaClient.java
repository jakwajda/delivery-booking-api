package com.company.delivery.client;

import com.company.delivery.config.CarrierProperties;
import com.company.delivery.dto.BookingRequest;
import com.company.delivery.model.Carrier;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class BetaClient implements CarrierClient {

    private final RestClient http;

    public BetaClient(CarrierProperties properties) {
        http = CarrierHttp.client(properties.betaUrl(), properties);
    }

    @Override
    public Carrier carrier() {
        return Carrier.BETA;
    }

    @Override
    public CarrierOutcome create(UUID reference, BookingRequest request) {
        return CarrierHttp.call(
                () ->
                        http.post()
                                .uri("/consignments")
                                .body(
                                        new Create(
                                                reference,
                                                address(request.sender()),
                                                address(request.recipient()),
                                                BigDecimal.valueOf(request.weightGrams(), 3),
                                                switch (request.serviceType()) {
                                                    case STANDARD -> "REGULAR";
                                                    case EXPRESS -> "PRIORITY";
                                                }))
                                .exchange(
                                        (req, res) -> {
                                            if (res.getStatusCode().is2xxSuccessful()) {
                                                return result(res.bodyTo(Result.class));
                                            }
                                            return CarrierHttp.error(
                                                    res.getStatusCode().value(),
                                                    false,
                                                    res.getHeaders().getFirst("Retry-After"));
                                        }));
    }

    @Override
    public CarrierOutcome lookup(UUID reference) {
        return CarrierHttp.call(
                () ->
                        http.get()
                                .uri("/consignments?reference={id}", reference)
                                .exchange(
                                        (req, res) -> {
                                            if (res.getStatusCode().is2xxSuccessful()) {
                                                return result(res.bodyTo(Result.class));
                                            }
                                            return CarrierHttp.error(
                                                    res.getStatusCode().value(),
                                                    true,
                                                    res.getHeaders().getFirst("Retry-After"));
                                        }));
    }

    private CarrierOutcome result(Result result) {
        return result == null
                ? CarrierOutcome.of(CarrierOutcome.Kind.UNKNOWN, "EMPTY_RESPONSE")
                : CarrierOutcome.success(result.number(), result.document());
    }

    private Address address(BookingRequest.Address value) {
        return new Address(value.country(), value.postalCode(), value.city(), value.street());
    }

    record Address(String countryCode, String zip, String town, String addressLine) {}

    record Create(
            UUID reference,
            Address origin,
            Address destination,
            BigDecimal massKg,
            String serviceLevel) {}

    record Result(String number, String document) {}
}

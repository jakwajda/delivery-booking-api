package com.company.delivery.client;

import com.company.delivery.config.CarrierProperties;
import com.company.delivery.dto.BookingRequest;
import com.company.delivery.model.Carrier;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class AlphaClient implements CarrierClient {

    private final RestClient http;

    public AlphaClient(CarrierProperties properties) {
        http = CarrierHttp.client(properties.alphaUrl(), properties);
    }

    @Override
    public Carrier carrier() {
        return Carrier.ALPHA;
    }

    @Override
    public CarrierOutcome create(UUID reference, BookingRequest request) {
        return CarrierHttp.call(
                () ->
                        http.post()
                                .uri("/shipments")
                                .header("Idempotency-Key", reference.toString())
                                .body(
                                        new Create(
                                                reference,
                                                request.sender(),
                                                request.recipient(),
                                                request.weightGrams(),
                                                request.serviceType()))
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
                                .uri("/shipments/by-reference/{id}", reference)
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
                : CarrierOutcome.success(result.trackingNumber(), result.labelUrl());
    }

    record Create(
            UUID clientReference,
            BookingRequest.Address sender,
            BookingRequest.Address recipient,
            int weightGrams,
            BookingRequest.ServiceType serviceType) {}

    record Result(String trackingNumber, String labelUrl) {}
}

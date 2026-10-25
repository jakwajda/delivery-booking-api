package com.company.delivery.client;

import com.company.delivery.config.CarrierProperties;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

final class CarrierHttp {

    private CarrierHttp() {}

    static RestClient client(URI url, CarrierProperties properties) {
        var http =
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder().baseUrl(url.toString()).requestFactory(factory).build();
    }

    static CarrierOutcome call(Supplier<CarrierOutcome> operation) {
        try {
            return operation.get();
        } catch (RestClientException | IllegalArgumentException exception) {
            // Provider payloads and exception messages can contain private address data.
            return CarrierOutcome.of(CarrierOutcome.Kind.UNKNOWN, "TRANSPORT_OR_RESPONSE_ERROR");
        }
    }

    static CarrierOutcome error(int status, boolean lookup, String retryAfter) {
        if (status == 404 && lookup) {
            return CarrierOutcome.of(CarrierOutcome.Kind.NOT_FOUND, "NOT_FOUND");
        }
        if (status == 422) {
            return CarrierOutcome.of(CarrierOutcome.Kind.REJECTED, "PROVIDER_REJECTED");
        }
        if (status == 401 || status == 403) {
            return CarrierOutcome.of(CarrierOutcome.Kind.CONFIG_ERROR, "PROVIDER_AUTH");
        }
        // Both fictional contracts explicitly guarantee that 429 has no side effect.
        if (status == 429) {
            return new CarrierOutcome(
                    CarrierOutcome.Kind.RETRYABLE,
                    null,
                    null,
                    "RATE_LIMITED",
                    retryDelay(retryAfter, Instant.now()));
        }
        return CarrierOutcome.of(CarrierOutcome.Kind.UNKNOWN, "HTTP_" + status);
    }

    static Duration retryDelay(String header, Instant now) {
        if (header == null) {
            return Duration.ZERO;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(header)));
        } catch (NumberFormatException exception) {
            try {
                var delay =
                        Duration.between(
                                now,
                                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                                        .toInstant());
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException invalid) {
                return Duration.ZERO;
            }
        }
    }
}

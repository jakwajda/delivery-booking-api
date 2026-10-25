package com.company.delivery.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.Locale;

public record BookingRequest(
        @NotBlank @Size(max = 100) String orderReference,
        @NotNull @Valid Address sender,
        @NotNull @Valid Address recipient,
        @Min(1) @Max(30_000) int weightGrams,
        @NotNull ServiceType serviceType) {
    public enum ServiceType {
        STANDARD,
        EXPRESS
    }

    public record Address(
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 200) String street) {
        public Address {
            country = country == null ? null : country.strip().toUpperCase(Locale.ROOT);
        }
    }
}

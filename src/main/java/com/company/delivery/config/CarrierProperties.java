package com.company.delivery.config;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("delivery.carriers")
public record CarrierProperties(
        @NotNull URI alphaUrl,
        @NotNull URI betaUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration requestTimeout) {
    public CarrierProperties {
        if (connectTimeout != null && (connectTimeout.isNegative() || connectTimeout.isZero())
                || requestTimeout != null
                        && (requestTimeout.isNegative() || requestTimeout.isZero())) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
    }
}

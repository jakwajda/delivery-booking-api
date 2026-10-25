package com.company.delivery.service;

import static org.assertj.core.api.Assertions.*;

import com.company.delivery.dto.BookingRequest.ServiceType;
import com.company.delivery.exception.BookingProblem;
import com.company.delivery.model.Carrier;
import com.company.delivery.support.BookingFixtures;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CarrierSelectorTest {

    private final CarrierSelector selector = new CarrierSelector();

    @ParameterizedTest
    @EnumSource(ServiceType.class)
    void routesAtWeightBoundaries(ServiceType type) {
        assertThat(selector.select(BookingFixtures.request(10_000, "PL", type)))
                .isEqualTo(Carrier.ALPHA);
        assertThat(selector.select(BookingFixtures.request(10_001, "PL", type)))
                .isEqualTo(Carrier.BETA);
        assertThat(selector.select(BookingFixtures.request(30_000, "DE", type)))
                .isEqualTo(Carrier.BETA);
        assertThatThrownBy(() -> selector.select(BookingFixtures.request(0, "PL", type)))
                .isInstanceOf(BookingProblem.class);
        assertThatThrownBy(() -> selector.select(BookingFixtures.request(30_001, "DE", type)))
                .isInstanceOf(BookingProblem.class);
    }
}

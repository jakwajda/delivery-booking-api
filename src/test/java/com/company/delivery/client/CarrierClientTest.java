package com.company.delivery.client;

import static com.company.delivery.support.BookingFixtures.request;

import static org.assertj.core.api.Assertions.*;

import com.company.delivery.config.CarrierProperties;
import com.company.delivery.dto.BookingRequest.ServiceType;
import com.company.delivery.support.CarrierStub;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.*;
import java.util.UUID;

class CarrierClientTest {

    private CarrierStub stub;
    private AlphaClient alpha;
    private BetaClient beta;

    @BeforeEach
    void setup() {
        stub = new CarrierStub();
        var properties =
                new CarrierProperties(
                        stub.url(), stub.url(), Duration.ofSeconds(1), Duration.ofSeconds(1));
        alpha = new AlphaClient(properties);
        beta = new BetaClient(properties);
    }

    @AfterEach
    void cleanup() {
        stub.close();
    }

    @ParameterizedTest
    @EnumSource(ServiceType.class)
    void mapsServiceTypeForBothCarriers(ServiceType type) {
        assertThat(alpha.create(UUID.randomUUID(), request(1_234, "PL", type)).kind())
                .isEqualTo(CarrierOutcome.Kind.SUCCESS);
        assertThat(stub.lastBody).contains("\"serviceType\":\"" + type.name() + "\"");
        assertThat(beta.create(UUID.randomUUID(), request(1_234, "DE", type)).kind())
                .isEqualTo(CarrierOutcome.Kind.SUCCESS);
        String level = type == ServiceType.EXPRESS ? "PRIORITY" : "REGULAR";
        assertThat(stub.lastBody).contains("\"serviceLevel\":\"" + level + "\"");
    }

    @Test
    void alphaDeduplicatesAfterLostResponse() {
        var id = UUID.randomUUID();
        stub.loseNextResponse.set(true);
        assertThat(alpha.create(id, request(1_234, "PL")).kind())
                .isEqualTo(CarrierOutcome.Kind.UNKNOWN);
        assertThat(alpha.create(id, request(1_234, "PL")).kind())
                .isEqualTo(CarrierOutcome.Kind.SUCCESS);
        assertThat(stub.creations.get()).isEqualTo(1);
    }

    @Test
    void betaTranslatesAndFindsSavedShipmentAfterLostResponse() {
        var id = UUID.randomUUID();
        stub.loseNextResponse.set(true);
        assertThat(beta.create(id, request(1_234, "DE")).kind())
                .isEqualTo(CarrierOutcome.Kind.UNKNOWN);
        assertThat(stub.lastBody).contains("\"massKg\":1.234", "\"countryCode\":\"DE\"");
        stub.hiddenLookups.set(1);
        assertThat(beta.lookup(id).kind()).isEqualTo(CarrierOutcome.Kind.NOT_FOUND);
        assertThat(beta.lookup(id).kind()).isEqualTo(CarrierOutcome.Kind.SUCCESS);
        assertThat(stub.createCalls.get()).isEqualTo(1);
    }

    @Test
    void classifiesErrorsWithoutAssumingServerErrorsAreSafe() {
        stub.status = 500;
        assertThat(beta.create(UUID.randomUUID(), request(1, "PL")).kind())
                .isEqualTo(CarrierOutcome.Kind.UNKNOWN);
        stub.status = 422;
        assertThat(alpha.create(UUID.randomUUID(), request(1, "PL")).kind())
                .isEqualTo(CarrierOutcome.Kind.REJECTED);
        stub.status = 401;
        assertThat(alpha.lookup(UUID.randomUUID()).kind())
                .isEqualTo(CarrierOutcome.Kind.CONFIG_ERROR);
        stub.status = 429;
        var result = beta.create(UUID.randomUUID(), request(1, "PL"));
        assertThat(result.kind()).isEqualTo(CarrierOutcome.Kind.RETRYABLE);
        assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void malformedSuccessHasUnknownOutcome() {
        stub.invalidResponse = true;
        assertThat(alpha.create(UUID.randomUUID(), request(1, "PL")).kind())
                .isEqualTo(CarrierOutcome.Kind.UNKNOWN);
    }

    @Test
    void parsesRetryAfterHttpDate() {
        assertThat(
                        CarrierHttp.retryDelay(
                                "Thu, 22 Oct 2026 12:00:02 GMT",
                                Instant.parse("2026-10-22T12:00:00Z")))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @Timeout(5)
    void readTimeoutLeavesExternalEffectUnknown() {
        stub.responseGate = new java.util.concurrent.CountDownLatch(1);
        var reference = UUID.randomUUID();
        try {
            assertThat(alpha.create(reference, request(1, "PL")).kind())
                    .isEqualTo(CarrierOutcome.Kind.UNKNOWN);
            assertThat(stub.creations.get()).isEqualTo(1);
        } finally {
            stub.responseGate.countDown();
        }
        assertThat(alpha.lookup(reference).kind()).isEqualTo(CarrierOutcome.Kind.SUCCESS);
    }
}

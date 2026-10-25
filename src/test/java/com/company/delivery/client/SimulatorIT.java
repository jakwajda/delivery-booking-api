package com.company.delivery.client;

import static com.company.delivery.support.BookingFixtures.request;

import static org.assertj.core.api.Assertions.*;

import com.company.delivery.config.CarrierProperties;
import com.company.delivery.dto.BookingRequest.ServiceType;
import com.company.delivery.model.Carrier;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Testcontainers
class SimulatorIT {

    @Container
    private static final GenericContainer<?> ALPHA = simulator("alpha");

    @Container
    private static final GenericContainer<?> BETA = simulator("beta");

    static GenericContainer<?> simulator(String carrier) {
        return new GenericContainer<>("python:3.13-alpine")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("simulators/carrier.py"), "/app/carrier.py")
                .withEnv("CARRIER", carrier)
                .withEnv("SIM_DB", "/tmp/shipments.db")
                .withCommand("python", "/app/carrier.py")
                .withExposedPorts(8_080)
                .waitingFor(Wait.forHttp("/health"));
    }

    URI url(GenericContainer<?> container) {
        return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8_080));
    }

    @Test
    void expressIsRecordedByBothSimulators() {
        var properties =
                new CarrierProperties(
                        url(ALPHA), url(BETA), Duration.ofSeconds(1), Duration.ofSeconds(2));
        for (var client :
                List.of(new AlphaClient(properties), new BetaClient(properties))) {
            var container = client.carrier() == Carrier.ALPHA ? ALPHA : BETA;
            var admin = RestClient.create(url(container).toString());
            admin.post()
                    .uri("/admin/scenario")
                    .body(Map.of("mode", "normal", "hideLookups", 0))
                    .retrieve()
                    .toBodilessEntity();
            int before =
                    ((Number)
                                    admin.get()
                                            .uri("/admin/stats")
                                            .retrieve()
                                            .body(Map.class)
                                            .get("expressShipments"))
                            .intValue();
            var reference = UUID.randomUUID();
            assertThat(client.create(reference, request(1_000, "PL", ServiceType.EXPRESS)).kind())
                    .isEqualTo(CarrierOutcome.Kind.SUCCESS);
            assertThat(client.lookup(reference).kind()).isEqualTo(CarrierOutcome.Kind.SUCCESS);
            assertThat(admin.get().uri("/admin/stats").retrieve().body(Map.class))
                    .containsEntry("expressShipments", before + 1);
        }
    }

    @Test
    void realSimulatorsSupportStreamingBodiesAndLostResponses() {
        var properties =
                new CarrierProperties(
                        url(ALPHA), url(BETA), Duration.ofSeconds(1), Duration.ofSeconds(2));
        var alpha = new AlphaClient(properties);
        var beta = new BetaClient(properties);
        var alphaAdmin = RestClient.create(url(ALPHA).toString());
        var betaAdmin = RestClient.create(url(BETA).toString());
        int alphaBefore =
                ((Number)
                                alphaAdmin
                                        .get()
                                        .uri("/admin/stats")
                                        .retrieve()
                                        .body(Map.class)
                                        .get("shipments"))
                        .intValue();
        int betaBefore =
                ((Number)
                                betaAdmin
                                        .get()
                                        .uri("/admin/stats")
                                        .retrieve()
                                        .body(Map.class)
                                        .get("shipments"))
                        .intValue();
        var reference = UUID.randomUUID();
        assertThat(alpha.create(reference, request(1, "PL")).kind())
                .isEqualTo(CarrierOutcome.Kind.SUCCESS);
        assertThat(alpha.create(reference, request(1, "PL")).kind())
                .isEqualTo(CarrierOutcome.Kind.SUCCESS);
        var admin = RestClient.create(url(BETA).toString());
        admin.post()
                .uri("/admin/scenario")
                .body(Map.of("mode", "lost-response", "hideLookups", 1))
                .retrieve()
                .toBodilessEntity();
        assertThat(beta.create(reference, request(1, "DE")).kind())
                .isEqualTo(CarrierOutcome.Kind.UNKNOWN);
        assertThat(beta.lookup(reference).kind()).isEqualTo(CarrierOutcome.Kind.NOT_FOUND);
        assertThat(beta.lookup(reference).kind()).isEqualTo(CarrierOutcome.Kind.SUCCESS);
        assertThat(admin.get().uri("/admin/stats").retrieve().body(Map.class))
                .containsEntry("shipments", betaBefore + 1);
        assertThat(
                        RestClient.create(url(ALPHA).toString())
                                .get()
                                .uri("/admin/stats")
                                .retrieve()
                                .body(Map.class))
                .containsEntry("shipments", alphaBefore + 1);
    }
}

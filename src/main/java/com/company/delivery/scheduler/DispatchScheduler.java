package com.company.delivery.scheduler;

import com.company.delivery.config.WorkerProperties;
import com.company.delivery.model.Carrier;
import com.company.delivery.service.DispatchProcessor;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "delivery.worker.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DispatchScheduler {

    private final DispatchProcessor processor;
    private final ExecutorService executor;
    private final Map<Carrier, Semaphore> slots = new EnumMap<>(Carrier.class);

    public DispatchScheduler(DispatchProcessor processor, WorkerProperties properties) {
        this.processor = processor;
        executor = Executors.newFixedThreadPool(Carrier.values().length * properties.concurrency());
        for (Carrier carrier : Carrier.values()) {
            slots.put(carrier, new Semaphore(properties.concurrency()));
        }
    }

    @Scheduled(fixedDelayString = "${delivery.worker.poll-delay:500}")
    public void poll() {
        slots.forEach(this::dispatchAvailable);
    }

    private void dispatchAvailable(Carrier carrier, Semaphore semaphore) {
        // Snapshot the budget: fast or empty attempts must not make this poll loop forever.
        int availableSlots = semaphore.availablePermits();
        for (int slot = 0; slot < availableSlots; slot++) {
            if (!semaphore.tryAcquire()) {
                break;
            }
            try {
                executor.execute(() -> runAttempt(carrier, semaphore));
            } catch (RejectedExecutionException shutdown) {
                semaphore.release();
                break;
            }
        }
    }

    private void runAttempt(Carrier carrier, Semaphore semaphore) {
        try {
            processor.runOne(carrier);
        } catch (RuntimeException error) {
            // Leave the lease for safe recovery. Never log provider payloads.
            log.error(
                    "Worker failed carrier={} exceptionType={}",
                    carrier,
                    error.getClass().getSimpleName());
        } finally {
            semaphore.release();
        }
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}

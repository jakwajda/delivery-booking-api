package com.company.delivery.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.company.delivery.config.WorkerProperties;
import com.company.delivery.model.Carrier;
import com.company.delivery.service.DispatchProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Timeout(10)
class DispatchSchedulerTest {

    private static final int CONCURRENCY = 3;
    private static final int TOTAL_SLOTS = Carrier.values().length * CONCURRENCY;

    private final DispatchProcessor processor = mock(DispatchProcessor.class);
    private final DispatchScheduler scheduler = new DispatchScheduler(
            processor,
            new WorkerProperties(
                    CONCURRENCY, Duration.ofSeconds(30), 5, Duration.ofHours(1),
                    Duration.ofSeconds(1), Duration.ofSeconds(30)));
    private final CountDownLatch releaseFirstWave = new CountDownLatch(1);
    private final CountDownLatch releaseSecondWave = new CountDownLatch(1);

    @AfterEach
    void stop() throws InterruptedException {
        releaseFirstWave.countDown();
        releaseSecondWave.countDown();
        scheduler.stop();
    }

    @Test
    void fillsAllSlotsPerCarrierAndReusesThemAfterCompletion() throws Exception {
        var firstStarted = new CountDownLatch(TOTAL_SLOTS);
        var secondStarted = new CountDownLatch(TOTAL_SLOTS);
        var calls = callCounters();
        doAnswer(invocation -> {
            Carrier carrier = invocation.getArgument(0);
            if (calls.get(carrier).incrementAndGet() <= CONCURRENCY) {
                firstStarted.countDown();
                releaseFirstWave.await();
            } else {
                secondStarted.countDown();
                releaseSecondWave.await();
            }
            return true;
        }).when(processor).runOne(any(Carrier.class));

        scheduler.poll();
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.poll();
        for (Carrier carrier : Carrier.values()) {
            verify(processor, times(CONCURRENCY)).runOne(carrier);
        }

        releaseFirstWave.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            scheduler.poll();
            return secondStarted.getCount() == 0;
        });
        for (Carrier carrier : Carrier.values()) {
            verify(processor, times(CONCURRENCY * 2)).runOne(carrier);
        }
    }

    @Test
    void failedAttemptsReleaseSlotsForTheNextPoll() throws Exception {
        var failed = new CountDownLatch(TOTAL_SLOTS);
        var nextStarted = new CountDownLatch(TOTAL_SLOTS);
        var calls = callCounters();
        doAnswer(invocation -> {
            Carrier carrier = invocation.getArgument(0);
            if (calls.get(carrier).incrementAndGet() <= CONCURRENCY) {
                failed.countDown();
                throw new IllegalStateException("simulated worker failure");
            }
            nextStarted.countDown();
            releaseSecondWave.await();
            return true;
        }).when(processor).runOne(any(Carrier.class));

        scheduler.poll();
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            scheduler.poll();
            return nextStarted.getCount() == 0;
        });
        for (Carrier carrier : Carrier.values()) {
            verify(processor, times(CONCURRENCY * 2)).runOne(carrier);
        }
    }

    @Test
    void emptyQueueDoesNotCauseUnboundedPolling() throws Exception {
        var checked = new CountDownLatch(TOTAL_SLOTS);
        doAnswer(invocation -> {
            checked.countDown();
            return false;
        }).when(processor).runOne(any(Carrier.class));

        scheduler.poll();

        assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
        for (Carrier carrier : Carrier.values()) {
            verify(processor, times(CONCURRENCY)).runOne(carrier);
        }
    }

    private Map<Carrier, AtomicInteger> callCounters() {
        Map<Carrier, AtomicInteger> counters = new EnumMap<>(Carrier.class);
        for (Carrier carrier : Carrier.values()) {
            counters.put(carrier, new AtomicInteger());
        }
        return counters;
    }
}

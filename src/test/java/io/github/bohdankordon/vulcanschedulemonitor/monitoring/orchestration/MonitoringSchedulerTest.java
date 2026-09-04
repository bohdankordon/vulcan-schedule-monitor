package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MonitoringSchedulerTest {

  @Test
  void overlappingLocalTriggerIsSkipped() throws Exception {
    MonitoringCycleRunner runner = mock(MonitoringCycleRunner.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(runner.runCycle())
        .thenAnswer(
            ignored -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              Instant now = Instant.parse("2026-09-04T10:00:00Z");
              return new MonitoringCycleSummary(now, now, 0, 0, false, List.of());
            });
    MonitoringScheduler scheduler = new MonitoringScheduler(runner);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(scheduler::poll);
      entered.await(5, TimeUnit.SECONDS);

      scheduler.poll();
      release.countDown();
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);

      verify(runner, times(1)).runCycle();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }
}

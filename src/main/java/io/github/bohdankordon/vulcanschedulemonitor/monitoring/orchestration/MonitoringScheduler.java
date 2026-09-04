package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Thin single-instance trigger; fixed delay and the guard prevent local cycle overlap. */
public final class MonitoringScheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(MonitoringScheduler.class);

  private final MonitoringCycleRunner runner;
  private final AtomicBoolean running = new AtomicBoolean();

  public MonitoringScheduler(MonitoringCycleRunner runner) {
    this.runner = Objects.requireNonNull(runner, "runner must not be null");
  }

  @Scheduled(
      fixedDelayString = "${vulcan.monitoring.poll-interval:PT5M}",
      initialDelayString = "${vulcan.monitoring.poll-interval:PT5M}")
  public void poll() {
    if (!running.compareAndSet(false, true)) {
      LOGGER.warn("Monitoring cycle trigger skipped because a cycle is already running");
      return;
    }
    try {
      runner.runCycle();
    } catch (RuntimeException exception) {
      LOGGER.error("Monitoring cycle failed before producing a summary");
    } finally {
      running.set(false);
    }
  }
}

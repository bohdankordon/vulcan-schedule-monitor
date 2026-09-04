package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.time.Duration;

@FunctionalInterface
public interface DelayStrategy {

  void delay(Duration duration) throws InterruptedException;
}

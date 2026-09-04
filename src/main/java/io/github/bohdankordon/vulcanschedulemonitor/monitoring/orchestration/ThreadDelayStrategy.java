package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.time.Duration;

public final class ThreadDelayStrategy implements DelayStrategy {

  @Override
  public void delay(Duration duration) throws InterruptedException {
    Thread.sleep(duration);
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public final class MutableClock extends Clock {

  private Instant instant;

  public MutableClock(Instant instant) {
    this.instant = instant;
  }

  public void advance(Duration duration) {
    instant = instant.plus(duration);
  }

  @Override
  public ZoneId getZone() {
    return ZoneId.of("UTC");
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return instant;
  }
}

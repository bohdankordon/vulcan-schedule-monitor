package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.time.Duration;

public enum EffectiveDelayBucket {
  ZERO,
  LE_10_SECONDS,
  LE_30_SECONDS,
  LE_60_SECONDS,
  LE_5_MINUTES,
  GT_5_MINUTES;

  public static EffectiveDelayBucket fromDuration(Duration duration) {
    if (duration == null || duration.compareTo(Duration.ZERO) <= 0) {
      return ZERO;
    }
    if (duration.compareTo(Duration.ofSeconds(10)) <= 0) {
      return LE_10_SECONDS;
    }
    if (duration.compareTo(Duration.ofSeconds(30)) <= 0) {
      return LE_30_SECONDS;
    }
    if (duration.compareTo(Duration.ofSeconds(60)) <= 0) {
      return LE_60_SECONDS;
    }
    if (duration.compareTo(Duration.ofMinutes(5)) <= 0) {
      return LE_5_MINUTES;
    }
    return GT_5_MINUTES;
  }
}

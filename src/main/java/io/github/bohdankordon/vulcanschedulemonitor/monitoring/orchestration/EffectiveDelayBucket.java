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
    if (duration == null || duration.isZero() || duration.isNegative()) {
      return ZERO;
    }
    long seconds = duration.toSeconds();
    if (seconds <= 10) {
      return LE_10_SECONDS;
    }
    if (seconds <= 30) {
      return LE_30_SECONDS;
    }
    if (seconds <= 60) {
      return LE_60_SECONDS;
    }
    if (seconds <= 300) {
      return LE_5_MINUTES;
    }
    return GT_5_MINUTES;
  }
}

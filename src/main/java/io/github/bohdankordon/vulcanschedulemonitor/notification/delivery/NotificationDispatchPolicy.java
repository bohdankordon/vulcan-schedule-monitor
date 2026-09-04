package io.github.bohdankordon.vulcanschedulemonitor.notification.delivery;

import java.time.Duration;
import java.util.Objects;

public record NotificationDispatchPolicy(
    int batchSize, Duration leaseDuration, int maxAttempts, Duration maximumRetryDelay) {

  public NotificationDispatchPolicy {
    Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
    Objects.requireNonNull(maximumRetryDelay, "maximumRetryDelay must not be null");
    if (batchSize <= 0 || maxAttempts <= 0) {
      throw new IllegalArgumentException("batchSize and maxAttempts must be positive");
    }
    if (leaseDuration.isZero()
        || leaseDuration.isNegative()
        || maximumRetryDelay.isZero()
        || maximumRetryDelay.isNegative()) {
      throw new IllegalArgumentException("Durations must be positive");
    }
  }

  public static NotificationDispatchPolicy defaults() {
    return new NotificationDispatchPolicy(25, Duration.ofMinutes(2), 5, Duration.ofMinutes(15));
  }
}

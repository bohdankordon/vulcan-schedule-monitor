package io.github.bohdankordon.vulcanschedulemonitor.notification.delivery;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class NotificationDeliveryException extends Exception {

  private final DeliveryFailureKind kind;
  private final Duration retryAfter;

  private NotificationDeliveryException(DeliveryFailureKind kind, Duration retryAfter) {
    super("Notification delivery failed: " + kind);
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
    this.retryAfter = retryAfter;
    if (retryAfter != null && retryAfter.isNegative()) {
      throw new IllegalArgumentException("retryAfter must not be negative");
    }
    if (kind == DeliveryFailureKind.PERMANENT && retryAfter != null) {
      throw new IllegalArgumentException("Permanent failure cannot carry retryAfter");
    }
  }

  public static NotificationDeliveryException retryable() {
    return new NotificationDeliveryException(DeliveryFailureKind.RETRYABLE, null);
  }

  public static NotificationDeliveryException retryable(Duration retryAfter) {
    return new NotificationDeliveryException(DeliveryFailureKind.RETRYABLE, retryAfter);
  }

  public static NotificationDeliveryException permanent() {
    return new NotificationDeliveryException(DeliveryFailureKind.PERMANENT, null);
  }

  public DeliveryFailureKind kind() {
    return kind;
  }

  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}

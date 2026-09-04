package io.github.bohdankordon.vulcanschedulemonitor.subscriptions;

import java.time.Instant;
import java.util.Objects;

public record MonitoringSubscription(
    long id,
    long appUserId,
    long catalogClassId,
    String className,
    String schoolUnit,
    int schoolYear,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {

  public MonitoringSubscription {
    if (id <= 0 || appUserId <= 0 || catalogClassId <= 0) {
      throw new IllegalArgumentException("Subscription identifiers must be positive");
    }
    if (className == null || className.isBlank()) {
      throw new IllegalArgumentException("Class name must be present");
    }
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }
}

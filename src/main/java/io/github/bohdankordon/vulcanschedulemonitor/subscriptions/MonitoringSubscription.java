package io.github.bohdankordon.vulcanschedulemonitor.subscriptions;

import java.time.Instant;
import java.util.Objects;

public record MonitoringSubscription(
    long id,
    long appUserId,
    long journalId,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {

  public MonitoringSubscription {
    if (id <= 0 || appUserId <= 0) {
      throw new IllegalArgumentException("Subscription and application user ids must be positive");
    }
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }
}

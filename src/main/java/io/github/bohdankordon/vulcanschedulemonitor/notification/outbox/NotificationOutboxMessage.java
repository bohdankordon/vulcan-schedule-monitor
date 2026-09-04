package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeMetadata;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import java.time.Instant;
import java.util.Objects;

public record NotificationOutboxMessage(
    long id,
    NotificationEventType eventType,
    TrackingScope scope,
    Integer activeChangeCount,
    String changeKey,
    ChangeMetadata changeMetadata,
    Instant occurredAt,
    int attemptNumber) {

  public NotificationOutboxMessage {
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    if (id <= 0 || attemptNumber <= 0) {
      throw new IllegalArgumentException(
          "Claimed message identifiers and attempts must be positive");
    }
    if (eventType == NotificationEventType.BASELINE_ESTABLISHED) {
      if (activeChangeCount == null
          || activeChangeCount < 0
          || changeKey != null
          || changeMetadata != null) {
        throw new IllegalArgumentException("Baseline message requires only an active-change count");
      }
    } else if (activeChangeCount != null || changeKey == null || changeMetadata == null) {
      throw new IllegalArgumentException("Change message requires key and metadata");
    }
  }
}

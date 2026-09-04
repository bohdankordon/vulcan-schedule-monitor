package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeMetadata;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record NotificationOutboxMessage(
    long id,
    long recipientUserId,
    long catalogClassId,
    long journalId,
    NotificationEventType eventType,
    LocalDate weekStart,
    LocalDate weekEnd,
    Integer activeChangeCount,
    String changeKey,
    ChangeMetadata changeMetadata,
    Instant occurredAt,
    int attemptNumber) {

  public NotificationOutboxMessage {
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(weekStart, "weekStart must not be null");
    Objects.requireNonNull(weekEnd, "weekEnd must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    if (id <= 0
        || recipientUserId <= 0
        || catalogClassId <= 0
        || journalId <= 0
        || attemptNumber <= 0) {
      throw new IllegalArgumentException(
          "Claimed message routing and attempt identifiers must be positive");
    }
    if (!weekEnd.equals(weekStart.plusDays(6))) {
      throw new IllegalArgumentException("Message week must cover seven days");
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

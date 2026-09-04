package io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeMetadata;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeType;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationEventType;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxMessage;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TelegramNotificationFormatterTest {

  private final TelegramNotificationFormatter formatter = new TelegramNotificationFormatter();

  @Test
  void formatsBaselineUsingOnlyMinimizedSafeFields() {
    String text = formatter.format(baseline());
    assertThat(text)
        .contains("baseline", "Schedule reference: #1001", "2026-08-31", "2026-09-06", "2")
        .doesNotContain("9001");
  }

  @Test
  void formatsEveryChangeLifecycleAndKnownChangeKinds() {
    assertThat(
            formatter.format(
                change(NotificationEventType.CHANGE_NEW, ChangeType.TEACHER_SUBSTITUTION)))
        .contains("New schedule change", "teacher substitution");
    assertThat(formatter.format(change(NotificationEventType.CHANGE_UPDATED, ChangeType.UNKNOWN)))
        .contains("Schedule change updated", "other schedule change");
    assertThat(formatter.format(change(NotificationEventType.CHANGE_RESOLVED, ChangeType.UNKNOWN)))
        .contains("Schedule change resolved");
  }

  @Test
  void excludesOpaqueAndInternalIdentifiers() {
    String text = formatter.format(change(NotificationEventType.CHANGE_NEW, ChangeType.UNKNOWN));
    assertThat(text)
        .contains("Schedule reference: #1001")
        .doesNotContain("9001", "internal-change-key", "8001", "7001", "6001", "lesson 8001");
  }

  private NotificationOutboxMessage baseline() {
    return new NotificationOutboxMessage(
        1,
        9001,
        NotificationEventType.BASELINE_ESTABLISHED,
        scope(),
        2,
        null,
        null,
        Instant.parse("2026-09-04T10:00:00Z"),
        1);
  }

  private NotificationOutboxMessage change(NotificationEventType event, ChangeType type) {
    return new NotificationOutboxMessage(
        1,
        9001,
        event,
        scope(),
        null,
        "internal-change-key",
        new ChangeMetadata(type, LocalDate.of(2026, 9, 2), 8001, 7001L, 6001L),
        Instant.parse("2026-09-04T10:00:00Z"),
        1);
  }

  private TrackingScope scope() {
    return new TrackingScope(1001, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6));
  }
}

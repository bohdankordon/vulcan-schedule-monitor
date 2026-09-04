package io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeMetadata;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeType;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationEventType;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxMessage;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TelegramNotificationFormatterTest {

  private final TelegramNotificationFormatter formatter = new TelegramNotificationFormatter();

  @Test
  void formatsBaselineUsingOnlyMinimizedSafeFields() {
    String text = formatter.format(baseline(), "Synthetic 2A");
    assertThat(text)
        .contains("baseline", "Class: Synthetic 2A", "2026-08-31", "2026-09-06", "2")
        .doesNotContain("9001", "1001", "7002");
  }

  @Test
  void formatsEveryChangeLifecycleAndKnownChangeKinds() {
    assertThat(
            formatter.format(
                change(NotificationEventType.CHANGE_NEW, ChangeType.TEACHER_SUBSTITUTION),
                "Synthetic 2A"))
        .contains("New schedule change", "teacher substitution");
    assertThat(
            formatter.format(
                change(NotificationEventType.CHANGE_UPDATED, ChangeType.UNKNOWN), "Synthetic 2A"))
        .contains("Schedule change updated", "other schedule change");
    assertThat(
            formatter.format(
                change(NotificationEventType.CHANGE_RESOLVED, ChangeType.UNKNOWN), "Synthetic 2A"))
        .contains("Schedule change resolved");
  }

  @Test
  void excludesOpaqueAndInternalIdentifiers() {
    String text =
        formatter.format(
            change(NotificationEventType.CHANGE_NEW, ChangeType.UNKNOWN), "Synthetic 2A");
    assertThat(text)
        .contains("Class: Synthetic 2A")
        .doesNotContain(
            "9001", "1001", "7002", "internal-change-key", "8001", "7001", "6001", "lesson 8001");
  }

  private NotificationOutboxMessage baseline() {
    return new NotificationOutboxMessage(
        1,
        9001,
        7002,
        1001,
        NotificationEventType.BASELINE_ESTABLISHED,
        LocalDate.of(2026, 8, 31),
        LocalDate.of(2026, 9, 6),
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
        7002,
        1001,
        event,
        LocalDate.of(2026, 8, 31),
        LocalDate.of(2026, 9, 6),
        null,
        "internal-change-key",
        new ChangeMetadata(type, LocalDate.of(2026, 9, 2), 8001, 7001L, 6001L),
        Instant.parse("2026-09-04T10:00:00Z"),
        1);
  }
}

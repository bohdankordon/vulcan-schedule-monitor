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
        .contains("Monitoring is ready", "Class: Synthetic 2A", "31.08.2026", "06.09.2026", "2")
        .doesNotContain("9001", "1001", "7002");
  }

  @Test
  void formatsEveryChangeLifecycleAndKnownChangeKinds() {
    assertThat(
            formatter.format(
                change(NotificationEventType.CHANGE_NEW, ChangeType.TEACHER_SUBSTITUTION),
                "Synthetic 2A"))
        .contains("New schedule change", "Teacher substitution", "02.09.2026");
    assertThat(
            formatter.format(
                change(NotificationEventType.CHANGE_UPDATED, ChangeType.UNKNOWN), "Synthetic 2A"))
        .contains("Schedule change updated", "Other schedule change", "02.09.2026");
    assertThat(
            formatter.format(
                change(NotificationEventType.CHANGE_RESOLVED, ChangeType.UNKNOWN), "Synthetic 2A"))
        .contains("Schedule change is no longer active", "02.09.2026");
  }

  @Test
  void formatsInAllSupportedLanguages() {
    var baselineMsg = baseline();
    var changeMsg = change(NotificationEventType.CHANGE_NEW, ChangeType.TEACHER_SUBSTITUTION);

    // English
    assertThat(
            formatter.format(
                baselineMsg,
                "Synthetic 2A",
                io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.ENGLISH))
        .contains(
            "✅ Monitoring is ready",
            "Class: Synthetic 2A",
            "31.08.2026 — 06.09.2026",
            "Active schedule changes: 2");
    assertThat(
            formatter.format(
                changeMsg,
                "Synthetic 2A",
                io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.ENGLISH))
        .contains(
            "🔔 New schedule change",
            "Class: Synthetic 2A",
            "Date: 02.09.2026",
            "Change: Teacher substitution");

    // Russian
    assertThat(
            formatter.format(
                baselineMsg,
                "Synthetic 2A",
                io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.RUSSIAN))
        .contains(
            "✅ Мониторинг настроен",
            "Класс: Synthetic 2A",
            "31.08.2026 — 06.09.2026",
            "Активных изменений: 2");
    assertThat(
            formatter.format(
                changeMsg,
                "Synthetic 2A",
                io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.RUSSIAN))
        .contains(
            "🔔 Новое изменение в расписании",
            "Класс: Synthetic 2A",
            "Дата: 02.09.2026",
            "Изменение: Замена учителя");

    // Ukrainian
    assertThat(
            formatter.format(
                baselineMsg,
                "Synthetic 2A",
                io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.UKRAINIAN))
        .contains(
            "✅ Моніторинг налаштовано",
            "Клас: Synthetic 2A",
            "31.08.2026 — 06.09.2026",
            "Активних змін: 2");
    assertThat(
            formatter.format(
                changeMsg,
                "Synthetic 2A",
                io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.UKRAINIAN))
        .contains(
            "🔔 Нова зміна в розкладі",
            "Клас: Synthetic 2A",
            "Дата: 02.09.2026",
            "Зміна: Заміна вчителя");

    // Polish
    assertThat(
            formatter.format(
                baselineMsg,
                "Synthetic 2A",
                io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.POLISH))
        .contains(
            "✅ Monitorowanie jest gotowe",
            "Klasa: Synthetic 2A",
            "31.08.2026 — 06.09.2026",
            "Aktywne zmiany w planie: 2");
    assertThat(
            formatter.format(
                changeMsg,
                "Synthetic 2A",
                io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.POLISH))
        .contains(
            "🔔 Nowa zmiana w planie",
            "Klasa: Synthetic 2A",
            "Data: 02.09.2026",
            "Zmiana: Zastępstwo nauczyciela");
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

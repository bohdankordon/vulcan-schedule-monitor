package io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeType;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxMessage;
import java.util.Objects;

public final class TelegramNotificationFormatter {

  public String format(NotificationOutboxMessage message, String className) {
    Objects.requireNonNull(message, "message must not be null");
    if (className == null || className.isBlank()) {
      throw new IllegalArgumentException("Class name must be present");
    }
    String schedule = "Class: " + className;
    return switch (message.eventType()) {
      case BASELINE_ESTABLISHED ->
          "Monitoring baseline established.\n"
              + schedule
              + "\nWeek: "
              + message.weekStart()
              + " to "
              + message.weekEnd()
              + "\nActive changes: "
              + message.activeChangeCount();
      case CHANGE_NEW -> change("New schedule change", schedule, message);
      case CHANGE_UPDATED -> change("Schedule change updated", schedule, message);
      case CHANGE_RESOLVED -> change("Schedule change resolved", schedule, message);
    };
  }

  private String change(String heading, String schedule, NotificationOutboxMessage message) {
    return heading
        + ".\n"
        + schedule
        + "\nDate: "
        + message.changeMetadata().lessonDate()
        + "\nChange type: "
        + changeType(message.changeMetadata().changeType());
  }

  private String changeType(ChangeType type) {
    return switch (type) {
      case TEACHER_SUBSTITUTION -> "teacher substitution";
      case UNKNOWN -> "other schedule change";
    };
  }
}

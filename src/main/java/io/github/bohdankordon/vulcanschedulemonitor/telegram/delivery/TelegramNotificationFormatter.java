package io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery;

import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxMessage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import java.util.Objects;

public final class TelegramNotificationFormatter {

  private final TelegramTextCatalog textCatalog;

  public TelegramNotificationFormatter() {
    this(new TelegramTextCatalog());
  }

  public TelegramNotificationFormatter(TelegramTextCatalog textCatalog) {
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
  }

  public String format(
      NotificationOutboxMessage message, String className, TelegramLanguage language) {
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(language, "language must not be null");
    if (className == null || className.isBlank()) {
      throw new IllegalArgumentException("Class name must be present");
    }
    return switch (message.eventType()) {
      case BASELINE_ESTABLISHED ->
          textCatalog.baselineNotification(
              language,
              className,
              message.weekStart(),
              message.weekEnd(),
              message.activeChangeCount());
      case CHANGE_NEW, CHANGE_UPDATED, CHANGE_RESOLVED ->
          textCatalog.changeNotification(
              language,
              message.eventType(),
              className,
              message.changeMetadata().lessonDate(),
              message.changeMetadata().changeType());
    };
  }

  public String format(NotificationOutboxMessage message, String className) {
    return format(message, className, TelegramLanguage.ENGLISH);
  }
}

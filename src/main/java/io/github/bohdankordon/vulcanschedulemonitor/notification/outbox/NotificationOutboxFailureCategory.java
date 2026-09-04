package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox;

public enum NotificationOutboxFailureCategory {
  RETRYABLE,
  PERMANENT,
  UNEXPECTED,
  EXHAUSTED
}

package io.github.bohdankordon.vulcanschedulemonitor.telegram.transport;

public enum TelegramFailureCategory {
  RATE_LIMITED,
  AUTHENTICATION,
  PERMANENT,
  TRANSIENT
}

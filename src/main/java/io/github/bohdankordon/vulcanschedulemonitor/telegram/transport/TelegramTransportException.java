package io.github.bohdankordon.vulcanschedulemonitor.telegram.transport;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class TelegramTransportException extends Exception {

  private final TelegramFailureCategory category;
  private final Duration retryAfter;

  public TelegramTransportException(TelegramFailureCategory category, Duration retryAfter) {
    super(
        "Telegram operation failed: "
            + Objects.requireNonNull(category, "category must not be null"));
    this.category = category;
    this.retryAfter = retryAfter;
  }

  public TelegramFailureCategory category() {
    return category;
  }

  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}

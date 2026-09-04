package io.github.bohdankordon.vulcanschedulemonitor.telegram.transport;

import java.time.Duration;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

public final class TelegramApiFailureClassifier {

  static final Duration FALLBACK_RATE_LIMIT_DELAY = Duration.ofSeconds(30);

  public TelegramTransportException classify(TelegramApiException exception) {
    if (!(exception instanceof TelegramApiRequestException requestException)) {
      return new TelegramTransportException(TelegramFailureCategory.TRANSIENT, null);
    }
    Integer code = requestException.getErrorCode();
    if (code == null) {
      return new TelegramTransportException(TelegramFailureCategory.TRANSIENT, null);
    }
    if (code == 429) {
      Integer seconds =
          requestException.getParameters() == null
              ? null
              : requestException.getParameters().getRetryAfter();
      Duration delay =
          seconds == null || seconds <= 0
              ? FALLBACK_RATE_LIMIT_DELAY
              : Duration.ofSeconds(seconds.longValue());
      return new TelegramTransportException(TelegramFailureCategory.RATE_LIMITED, delay);
    }
    if (code == 401) {
      return new TelegramTransportException(TelegramFailureCategory.AUTHENTICATION, null);
    }
    if (code >= 400 && code < 500) {
      return new TelegramTransportException(TelegramFailureCategory.PERMANENT, null);
    }
    return new TelegramTransportException(TelegramFailureCategory.TRANSIENT, null);
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.telegram.transport;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailabilityGate;
import java.util.Objects;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public final class TelegramBotsMessageTransport implements TelegramMessageTransport, AutoCloseable {

  private final OkHttpClient httpClient;
  private final TelegramClient telegramClient;
  private final TelegramApiFailureClassifier classifier;
  private final TelegramProviderAvailabilityGate gate;

  public TelegramBotsMessageTransport(String token, TelegramProviderAvailabilityGate gate) {
    this(new OkHttpClient(), token, gate, new TelegramApiFailureClassifier());
  }

  TelegramBotsMessageTransport(
      OkHttpClient httpClient,
      String token,
      TelegramProviderAvailabilityGate gate,
      TelegramApiFailureClassifier classifier) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.telegramClient = new OkHttpTelegramClient(httpClient, token);
    this.gate = Objects.requireNonNull(gate, "gate must not be null");
    this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
  }

  @Override
  public void sendPlainText(long privateChatId, String text) throws TelegramTransportException {
    if (!gate.isAvailable()) {
      throw new TelegramTransportException(TelegramFailureCategory.TRANSIENT, null);
    }
    try {
      telegramClient.execute(SendMessage.builder().chatId(privateChatId).text(text).build());
    } catch (TelegramApiException exception) {
      TelegramTransportException failure = classifier.classify(exception);
      if (failure.category() == TelegramFailureCategory.RATE_LIMITED) {
        gate.defer(failure.retryAfter().orElseThrow());
      } else if (failure.category() == TelegramFailureCategory.AUTHENTICATION) {
        gate.suspendUntilRestart();
      }
      throw failure;
    }
  }

  @Override
  public void close() {
    httpClient.dispatcher().executorService().shutdown();
    httpClient.connectionPool().evictAll();
    if (httpClient.cache() != null) {
      try {
        httpClient.cache().close();
      } catch (java.io.IOException ignored) {
        // Best-effort cleanup of an optional cache.
      }
    }
  }
}

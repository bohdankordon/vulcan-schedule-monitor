package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramApiFailureClassifier;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public final class TelegramBotsLongPollingEngine implements TelegramLongPollingEngine {

  private final ScheduledExecutorService executor;
  private final OkHttpClient httpClient;
  private final TelegramBotsLongPollingApplication application;
  private final TelegramApiFailureClassifier classifier;

  public TelegramBotsLongPollingEngine() {
    this(newOwnedExecutor(), new OkHttpClient(), new TelegramApiFailureClassifier());
  }

  static ScheduledExecutorService newOwnedExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "telegram-long-polling");
          thread.setDaemon(false);
          return thread;
        });
  }

  TelegramBotsLongPollingEngine(
      ScheduledExecutorService executor,
      OkHttpClient httpClient,
      TelegramApiFailureClassifier classifier) {
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
    this.application =
        new TelegramBotsLongPollingApplication(ObjectMapper::new, () -> httpClient, () -> executor);
  }

  @Override
  public void start(String token, LongPollingUpdateConsumer consumer)
      throws TelegramTransportException {
    try {
      application.registerBot(token, consumer);
    } catch (TelegramApiException exception) {
      throw classifier.classify(exception);
    }
  }

  @Override
  public void close() {
    try {
      application.close();
    } catch (Exception ignored) {
      // Resource shutdown below must proceed even if a bot session fails to close.
    } finally {
      executor.shutdownNow();
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

  boolean executorShutdown() {
    return executor.isShutdown();
  }
}

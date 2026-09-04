package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramApiFailureClassifier;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public final class TelegramBotsLongPollingEngine implements TelegramLongPollingEngine {

  private final ScheduledExecutorService executor;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final TelegramBotsLongPollingApplication application;
  private final TelegramApiFailureClassifier classifier;
  private final TelegramUrl telegramUrl;

  public TelegramBotsLongPollingEngine() {
    this(
        newOwnedExecutor(),
        new OkHttpClient(),
        new TelegramApiFailureClassifier(),
        TelegramUrl.DEFAULT_URL);
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
    this(executor, httpClient, classifier, TelegramUrl.DEFAULT_URL);
  }

  TelegramBotsLongPollingEngine(
      ScheduledExecutorService executor,
      OkHttpClient httpClient,
      TelegramApiFailureClassifier classifier,
      TelegramUrl telegramUrl) {
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
    this.telegramUrl = Objects.requireNonNull(telegramUrl, "telegramUrl must not be null");
    this.objectMapper = new ObjectMapper();
    this.application =
        new TelegramBotsLongPollingApplication(
            () -> objectMapper, () -> httpClient, () -> executor);
  }

  @Override
  public void start(String token, LongPollingUpdateConsumer consumer)
      throws TelegramTransportException {
    try {
      new OkHttpTelegramClient(objectMapper, httpClient, token, telegramUrl).execute(new GetMe());
      application.registerBot(token, () -> telegramUrl, new DefaultGetUpdatesGenerator(), consumer);
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

  boolean httpDispatcherShutdown() {
    return httpClient.dispatcher().executorService().isShutdown();
  }

  boolean awaitExecutorTermination(Duration timeout) throws InterruptedException {
    return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }
}

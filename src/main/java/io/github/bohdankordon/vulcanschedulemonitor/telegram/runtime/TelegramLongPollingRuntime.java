package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailabilityGate;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;

public final class TelegramLongPollingRuntime implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(TelegramLongPollingRuntime.class);
  private static final List<Duration> RETRY_DELAYS =
      List.of(
          Duration.ofSeconds(5),
          Duration.ofSeconds(15),
          Duration.ofSeconds(45),
          Duration.ofMinutes(2));

  private final String token;
  private final TelegramLongPollingEngineFactory engineFactory;
  private final LongPollingUpdateConsumer consumer;
  private final TelegramProviderAvailabilityGate gate;
  private final Clock clock;
  private TelegramLongPollingEngine runningEngine;
  private Instant nextAttemptAt = Instant.MIN;
  private int failures;

  public TelegramLongPollingRuntime(
      String token,
      TelegramLongPollingEngineFactory engineFactory,
      LongPollingUpdateConsumer consumer,
      TelegramProviderAvailabilityGate gate,
      Clock clock) {
    this.token = Objects.requireNonNull(token, "token must not be null");
    this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory must not be null");
    this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
    this.gate = Objects.requireNonNull(gate, "gate must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public synchronized void tryStartIfDue() {
    if (runningEngine != null || !gate.isAvailable() || clock.instant().isBefore(nextAttemptAt)) {
      return;
    }
    TelegramLongPollingEngine candidate = null;
    try {
      candidate = engineFactory.create();
      candidate.start(token, consumer);
      runningEngine = candidate;
      failures = 0;
      LOGGER.info("Telegram long-polling runtime connected");
    } catch (TelegramTransportException failure) {
      close(candidate);
      if (failure.category() == TelegramFailureCategory.AUTHENTICATION) {
        gate.suspendUntilRestart();
        LOGGER.error(
            "Telegram bot authentication failed; Telegram runtime suspended until restart");
        return;
      }
      if (failure.category() == TelegramFailureCategory.RATE_LIMITED) {
        gate.defer(failure.retryAfter().orElse(Duration.ofSeconds(30)));
      }
      Duration delay = RETRY_DELAYS.get(Math.min(failures, RETRY_DELAYS.size() - 1));
      failures++;
      nextAttemptAt = clock.instant().plus(delay);
      LOGGER.warn(
          "Telegram runtime registration failed; retry deferred, category={}", failure.category());
    } catch (RuntimeException failure) {
      close(candidate);
      Duration delay = RETRY_DELAYS.get(Math.min(failures, RETRY_DELAYS.size() - 1));
      failures++;
      nextAttemptAt = clock.instant().plus(delay);
      LOGGER.warn("Telegram runtime registration failed; retry deferred, category=TRANSIENT");
    }
  }

  public synchronized boolean isRunning() {
    return runningEngine != null;
  }

  @Override
  public synchronized void close() {
    close(runningEngine);
    runningEngine = null;
    LOGGER.info("Telegram long-polling runtime disconnected");
  }

  private void close(TelegramLongPollingEngine engine) {
    if (engine != null) {
      engine.close();
    }
  }
}

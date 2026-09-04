package io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery;

import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationOutboxDispatcher;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailabilityGate;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class TelegramNotificationDispatchScheduler {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TelegramNotificationDispatchScheduler.class);

  private final NotificationOutboxDispatcher dispatcher;
  private final TelegramProviderAvailabilityGate gate;
  private final AtomicBoolean running = new AtomicBoolean();

  public TelegramNotificationDispatchScheduler(
      NotificationOutboxDispatcher dispatcher, TelegramProviderAvailabilityGate gate) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
    this.gate = Objects.requireNonNull(gate, "gate must not be null");
  }

  @Scheduled(
      fixedDelayString = "${telegram.dispatch.interval:PT2S}",
      initialDelayString = "${telegram.dispatch.initial-delay:PT2S}")
  public void dispatch() {
    if (!gate.isAvailable() || !running.compareAndSet(false, true)) {
      return;
    }
    try {
      var summary = dispatcher.dispatchOnce();
      if (summary.claimed() > 0) {
        LOGGER.info(
            "Telegram dispatch completed: claimed={}, delivered={}, retried={}, dead={}",
            summary.claimed(),
            summary.delivered(),
            summary.retried(),
            summary.dead());
      }
    } catch (RuntimeException exception) {
      LOGGER.error("Telegram dispatch tick failed");
    } finally {
      running.set(false);
    }
  }
}

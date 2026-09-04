package io.github.bohdankordon.vulcanschedulemonitor.telegram.update;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

public final class TelegramUpdateConsumer implements LongPollingUpdateConsumer {

  private static final Logger LOGGER = LoggerFactory.getLogger(TelegramUpdateConsumer.class);
  private final TelegramUpdateRouter router;

  public TelegramUpdateConsumer(TelegramUpdateRouter router) {
    this.router = Objects.requireNonNull(router, "router must not be null");
  }

  @Override
  public void consume(List<Update> updates) {
    if (updates == null) {
      return;
    }
    for (Update update : updates) {
      try {
        router.route(update);
      } catch (RuntimeException failure) {
        Integer updateId = update == null ? null : update.getUpdateId();
        LOGGER.warn("Telegram update failed: updateId={}", updateId);
      }
    }
  }
}

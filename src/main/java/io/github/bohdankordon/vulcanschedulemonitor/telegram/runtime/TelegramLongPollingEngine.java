package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;

public interface TelegramLongPollingEngine extends AutoCloseable {

  void start(String token, LongPollingUpdateConsumer consumer) throws TelegramTransportException;

  @Override
  void close();
}

package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;

public interface TelegramInteractiveTransport {

  void send(long privateChatId, TelegramInteractiveMessage message)
      throws TelegramTransportException;

  void edit(long privateChatId, int messageId, TelegramInteractiveMessage message)
      throws TelegramTransportException;

  void answerCallback(String callbackQueryId, String text) throws TelegramTransportException;
}

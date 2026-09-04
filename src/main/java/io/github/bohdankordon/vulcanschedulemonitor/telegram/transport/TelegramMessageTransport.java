package io.github.bohdankordon.vulcanschedulemonitor.telegram.transport;

public interface TelegramMessageTransport {

  void sendPlainText(long privateChatId, String text) throws TelegramTransportException;
}

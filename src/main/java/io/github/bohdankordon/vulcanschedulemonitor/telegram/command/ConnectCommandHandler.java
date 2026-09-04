package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

public final class ConnectCommandHandler implements TelegramCommandHandler {

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.CONNECT;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    return TelegramTexts.CONNECT;
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

public final class StartCommandHandler implements TelegramCommandHandler {

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.START;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    return TelegramTexts.START;
  }
}

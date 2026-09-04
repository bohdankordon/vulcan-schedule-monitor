package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

public final class HelpCommandHandler implements TelegramCommandHandler {

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.HELP;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    return TelegramTexts.HELP;
  }
}

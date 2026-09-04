package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

public interface TelegramCommandHandler {

  TelegramCommand supportedCommand();

  String handle(TelegramCommandContext context);
}

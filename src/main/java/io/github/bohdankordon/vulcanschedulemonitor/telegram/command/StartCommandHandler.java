package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import java.util.Objects;

public final class StartCommandHandler implements TelegramCommandHandler {

  private final TelegramTextCatalog textCatalog;

  public StartCommandHandler() {
    this(new TelegramTextCatalog());
  }

  public StartCommandHandler(TelegramTextCatalog textCatalog) {
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
  }

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.START;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    return textCatalog.welcome(context.language());
  }
}

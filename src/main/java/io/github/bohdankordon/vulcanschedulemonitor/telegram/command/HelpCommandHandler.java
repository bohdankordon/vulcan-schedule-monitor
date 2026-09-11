package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import java.util.Objects;

public final class HelpCommandHandler implements TelegramCommandHandler {

  private final TelegramTextCatalog textCatalog;

  public HelpCommandHandler() {
    this(new TelegramTextCatalog());
  }

  public HelpCommandHandler(TelegramTextCatalog textCatalog) {
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
  }

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.HELP;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    return textCatalog.help(context.language());
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import java.util.Locale;
import java.util.Optional;

public final class TelegramCommandParser {

  public Optional<TelegramCommand> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String normalized = text.strip();
    if (!normalized.startsWith("/") || normalized.length() == 1) {
      return Optional.empty();
    }
    int whitespace = firstWhitespace(normalized);
    String commandToken =
        whitespace < 0 ? normalized.substring(1) : normalized.substring(1, whitespace);
    int suffix = commandToken.indexOf('@');
    String name =
        (suffix < 0 ? commandToken : commandToken.substring(0, suffix)).toUpperCase(Locale.ROOT);
    try {
      return Optional.of(TelegramCommand.valueOf(name));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  private int firstWhitespace(String value) {
    for (int index = 0; index < value.length(); index++) {
      if (Character.isWhitespace(value.charAt(index))) {
        return index;
      }
    }
    return -1;
  }
}

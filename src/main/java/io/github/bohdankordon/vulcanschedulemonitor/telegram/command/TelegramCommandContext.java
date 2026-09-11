package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import java.util.Objects;

public record TelegramCommandContext(
    long appUserId, long privateChatId, TelegramLanguage language) {

  public TelegramCommandContext(long appUserId, long privateChatId) {
    this(appUserId, privateChatId, TelegramLanguage.ENGLISH);
  }

  public TelegramCommandContext {
    Objects.requireNonNull(language, "language must not be null");
  }
}

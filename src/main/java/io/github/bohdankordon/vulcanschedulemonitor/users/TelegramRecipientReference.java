package io.github.bohdankordon.vulcanschedulemonitor.users;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import java.util.Objects;

public record TelegramRecipientReference(
    long telegramUserId, long privateChatId, TelegramLanguage language) {

  public TelegramRecipientReference(long telegramUserId, long privateChatId) {
    this(telegramUserId, privateChatId, TelegramLanguage.ENGLISH);
  }

  public TelegramRecipientReference {
    Objects.requireNonNull(language, "language must not be null");
  }
}

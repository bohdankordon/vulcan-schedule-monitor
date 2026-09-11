package io.github.bohdankordon.vulcanschedulemonitor.users;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;

public interface TelegramLanguagePreferences {

  TelegramLanguage getLanguage(long appUserId);

  void setLanguage(long appUserId, TelegramLanguage language);
}

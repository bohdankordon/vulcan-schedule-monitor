package io.github.bohdankordon.vulcanschedulemonitor.telegram;

import java.util.Objects;

public enum TelegramLanguage {
  ENGLISH("en"),
  RUSSIAN("ru"),
  UKRAINIAN("uk"),
  POLISH("pl");

  private final String code;

  TelegramLanguage(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static TelegramLanguage fromCode(String code) {
    Objects.requireNonNull(code, "Language code must not be null");
    for (TelegramLanguage language : values()) {
      if (language.code.equals(code)) {
        return language;
      }
    }
    throw new IllegalArgumentException("Unsupported Telegram language code: " + code);
  }
}

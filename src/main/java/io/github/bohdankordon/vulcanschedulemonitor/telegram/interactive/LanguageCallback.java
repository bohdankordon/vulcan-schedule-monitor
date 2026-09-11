package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import java.util.Objects;

public record LanguageCallback(Source source, TelegramLanguage language) {

  public enum Source {
    START("s"),
    COMMAND("c");

    private final String code;

    Source(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }

    public static Source fromCode(String code) {
      Objects.requireNonNull(code, "code must not be null");
      for (Source s : values()) {
        if (s.code.equals(code)) {
          return s;
        }
      }
      throw new IllegalArgumentException("Unknown language callback source: " + code);
    }
  }

  public LanguageCallback {
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(language, "language must not be null");
  }
}

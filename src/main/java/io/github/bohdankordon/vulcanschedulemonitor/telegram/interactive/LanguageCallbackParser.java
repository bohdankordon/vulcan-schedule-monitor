package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class LanguageCallbackParser {

  private static final int MAX_BYTES = 64;

  public Optional<LanguageCallback> parse(String data) {
    if (data == null
        || data.isBlank()
        || data.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      return Optional.empty();
    }
    String[] parts = data.split(":", -1);
    if (parts.length != 3 || !"l1".equals(parts[0])) {
      return Optional.empty();
    }
    try {
      LanguageCallback.Source source = LanguageCallback.Source.fromCode(parts[1]);
      TelegramLanguage language = TelegramLanguage.fromCode(parts[2]);
      return Optional.of(new LanguageCallback(source, language));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }
}

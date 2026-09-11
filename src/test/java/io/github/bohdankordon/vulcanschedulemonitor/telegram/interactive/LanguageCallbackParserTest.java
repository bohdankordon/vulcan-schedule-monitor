package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LanguageCallbackParserTest {

  private final LanguageCallbackParser parser = new LanguageCallbackParser();

  @ParameterizedTest
  @CsvSource({
    "l1:s:en, START, ENGLISH",
    "l1:s:ru, START, RUSSIAN",
    "l1:s:uk, START, UKRAINIAN",
    "l1:s:pl, START, POLISH",
    "l1:c:en, COMMAND, ENGLISH",
    "l1:c:ru, COMMAND, RUSSIAN",
    "l1:c:uk, COMMAND, UKRAINIAN",
    "l1:c:pl, COMMAND, POLISH"
  })
  void parsesValidCallbacks(
      String data, LanguageCallback.Source source, TelegramLanguage language) {
    var parsed = parser.parse(data);
    assertThat(parsed).isPresent();
    assertThat(parsed.get().source()).isEqualTo(source);
    assertThat(parsed.get().language()).isEqualTo(language);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "l1",
        "l1:",
        "l1:s",
        "l1:c",
        "l1:x:en",
        "l1:s:ua",
        "l1:s:de",
        "l2:s:en",
        "c1:t:101:0",
        "c1:p:0",
        "",
        "   ",
        "arbitrary text",
        "l1:s:en:extra",
        "l1:s:"
      })
  void rejectsMalformedCallbacksSafely(String data) {
    assertThat(parser.parse(data)).isEmpty();
  }

  @Test
  void rejectsNullAndOversizedPayloads() {
    assertThat(parser.parse(null)).isEmpty();
    assertThat(parser.parse("l1:s:" + "a".repeat(70))).isEmpty();
  }
}

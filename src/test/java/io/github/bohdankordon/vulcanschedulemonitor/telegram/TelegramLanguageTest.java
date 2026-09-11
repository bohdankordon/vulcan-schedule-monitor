package io.github.bohdankordon.vulcanschedulemonitor.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelegramLanguageTest {

  @Test
  void parsesSupportedCodesDeterministically() {
    assertThat(TelegramLanguage.fromCode("en")).isEqualTo(TelegramLanguage.ENGLISH);
    assertThat(TelegramLanguage.fromCode("ru")).isEqualTo(TelegramLanguage.RUSSIAN);
    assertThat(TelegramLanguage.fromCode("uk")).isEqualTo(TelegramLanguage.UKRAINIAN);
    assertThat(TelegramLanguage.fromCode("pl")).isEqualTo(TelegramLanguage.POLISH);

    assertThat(TelegramLanguage.ENGLISH.code()).isEqualTo("en");
    assertThat(TelegramLanguage.RUSSIAN.code()).isEqualTo("ru");
    assertThat(TelegramLanguage.UKRAINIAN.code()).isEqualTo("uk");
    assertThat(TelegramLanguage.POLISH.code()).isEqualTo("pl");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ua", "de", "es", "fr", "", "  ", "EN", "RU", "UK", "PL", "english"})
  void rejectsUnsupportedCodes(String code) {
    assertThatThrownBy(() -> TelegramLanguage.fromCode(code))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Telegram language code");
  }

  @Test
  void rejectsNullCode() {
    assertThatThrownBy(() -> TelegramLanguage.fromCode(null))
        .isInstanceOf(NullPointerException.class);
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TelegramConfigurationTest {

  @Test
  void enabledBotRejectsBlankTokenWithSanitizedConfigurationFailure() {
    new ApplicationContextRunner()
        .withUserConfiguration(TelegramConfiguration.class)
        .withPropertyValues("telegram.bot.enabled=true", "telegram.bot.token=   ")
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable root = context.getStartupFailure();
              while (root.getCause() != null) {
                root = root.getCause();
              }
              assertThat(root)
                  .hasMessageContaining("TELEGRAM_BOT_TOKEN is blank")
                  .hasMessageNotContaining("telegram.bot.token=   ");
            });
  }

  @Test
  void tokenBearingPropertiesRedactToString() {
    var properties = new TelegramBotProperties();
    properties.setEnabled(true);
    properties.setToken("synthetic-secret-value");
    assertThat(properties.toString())
        .contains("enabled=true", "<redacted>")
        .doesNotContain("synthetic-secret-value");
  }
}

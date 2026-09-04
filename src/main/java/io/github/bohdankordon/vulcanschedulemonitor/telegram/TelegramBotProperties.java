package io.github.bohdankordon.vulcanschedulemonitor.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("telegram.bot")
public final class TelegramBotProperties {

  private boolean enabled;
  private String token = "";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token == null ? "" : token;
  }

  @Override
  public String toString() {
    return "TelegramBotProperties{enabled=" + enabled + ", token=<redacted>}";
  }
}

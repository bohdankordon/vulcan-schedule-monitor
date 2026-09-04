package io.github.bohdankordon.vulcanschedulemonitor.telegram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("telegram.dispatch")
public final class TelegramDispatchProperties {

  private Duration interval = Duration.ofSeconds(2);
  private Duration initialDelay = Duration.ofSeconds(2);

  public Duration getInterval() {
    return interval;
  }

  public void setInterval(Duration interval) {
    this.interval = interval;
  }

  public Duration getInitialDelay() {
    return initialDelay;
  }

  public void setInitialDelay(Duration initialDelay) {
    this.initialDelay = initialDelay;
  }
}

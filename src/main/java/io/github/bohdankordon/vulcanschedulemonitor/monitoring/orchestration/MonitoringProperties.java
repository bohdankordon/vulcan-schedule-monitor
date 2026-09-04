package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vulcan.monitoring")
public class MonitoringProperties {

  private boolean enabled;
  private Duration pollInterval = Duration.ofMinutes(5);
  private Duration requestSpacing = Duration.ofMillis(500);
  private int maxAttempts = 3;
  private Duration initialRetryBackoff = Duration.ofSeconds(1);
  private Duration fallbackRateLimitDelay = Duration.ofSeconds(30);
  private Duration maximumInlineRateLimitDelay = Duration.ofSeconds(10);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
  }

  public Duration getRequestSpacing() {
    return requestSpacing;
  }

  public void setRequestSpacing(Duration requestSpacing) {
    this.requestSpacing = requestSpacing;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getInitialRetryBackoff() {
    return initialRetryBackoff;
  }

  public void setInitialRetryBackoff(Duration initialRetryBackoff) {
    this.initialRetryBackoff = initialRetryBackoff;
  }

  public Duration getFallbackRateLimitDelay() {
    return fallbackRateLimitDelay;
  }

  public void setFallbackRateLimitDelay(Duration fallbackRateLimitDelay) {
    this.fallbackRateLimitDelay = fallbackRateLimitDelay;
  }

  public Duration getMaximumInlineRateLimitDelay() {
    return maximumInlineRateLimitDelay;
  }

  public void setMaximumInlineRateLimitDelay(Duration maximumInlineRateLimitDelay) {
    this.maximumInlineRateLimitDelay = maximumInlineRateLimitDelay;
  }
}

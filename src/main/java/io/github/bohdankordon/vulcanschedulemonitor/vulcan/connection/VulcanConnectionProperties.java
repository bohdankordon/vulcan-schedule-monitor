package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vulcan.connection")
public class VulcanConnectionProperties {

  private boolean enabled;
  private URI publicBaseUrl;
  private Duration tokenTtl = Duration.ofMinutes(10);
  private int maxCredentialAttempts = 5;
  private String masterKey = "";
  private boolean playwrightHeadless = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public URI getPublicBaseUrl() {
    return publicBaseUrl;
  }

  public void setPublicBaseUrl(URI publicBaseUrl) {
    this.publicBaseUrl = publicBaseUrl;
  }

  public Duration getTokenTtl() {
    return tokenTtl;
  }

  public void setTokenTtl(Duration tokenTtl) {
    this.tokenTtl = tokenTtl;
  }

  public int getMaxCredentialAttempts() {
    return maxCredentialAttempts;
  }

  public void setMaxCredentialAttempts(int maxCredentialAttempts) {
    this.maxCredentialAttempts = maxCredentialAttempts;
  }

  public String getMasterKey() {
    return masterKey;
  }

  public void setMasterKey(String masterKey) {
    this.masterKey = masterKey;
  }

  public boolean isPlaywrightHeadless() {
    return playwrightHeadless;
  }

  public void setPlaywrightHeadless(boolean playwrightHeadless) {
    this.playwrightHeadless = playwrightHeadless;
  }

  @Override
  public String toString() {
    return "VulcanConnectionProperties[enabled=" + enabled + ", secrets=[redacted]]";
  }
}

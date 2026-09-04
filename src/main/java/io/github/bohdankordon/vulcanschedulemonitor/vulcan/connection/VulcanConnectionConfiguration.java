package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.JpaVulcanConnectTokenService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.VulcanConnectionCompletion;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.VulcanRecoveryPersistence;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright.PlaywrightVulcanBrowserAuthenticator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.AesGcmCipher;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.SecretPayloadCodec;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanMasterKey;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VulcanConnectionProperties.class)
public class VulcanConnectionConfiguration {

  public VulcanConnectionConfiguration(VulcanConnectionProperties properties) {
    if (properties.isEnabled()) {
      validateEnabled(properties);
    }
  }

  @Bean
  @ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
  VulcanMasterKey vulcanMasterKey(VulcanConnectionProperties properties) {
    return VulcanMasterKey.fromBase64(properties.getMasterKey());
  }

  @Bean
  @ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
  AesGcmCipher aesGcmCipher(VulcanMasterKey masterKey) {
    return new AesGcmCipher(masterKey);
  }

  @Bean
  @ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
  SecretPayloadCodec secretPayloadCodec() {
    return new SecretPayloadCodec();
  }

  @Bean
  @ConditionalOnMissingBean(VulcanSessionVerifier.class)
  @ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
  VulcanSessionVerifier vulcanSessionVerifier() {
    return new DefaultVulcanSessionVerifier();
  }

  @Bean
  @ConditionalOnMissingBean(VulcanBrowserAuthenticator.class)
  @ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
  VulcanBrowserAuthenticator vulcanBrowserAuthenticator(
      PortalUrlValidator portalUrls, VulcanConnectionProperties properties) {
    return new PlaywrightVulcanBrowserAuthenticator(portalUrls, properties.isPlaywrightHeadless());
  }

  @Bean
  @ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
  VulcanConnectionService vulcanConnectionService(
      JpaVulcanConnectTokenService tokens,
      PortalUrlValidator portalUrls,
      VulcanBrowserAuthenticator authenticator,
      VulcanSessionVerifier verifier,
      VulcanConnectionCompletion completion) {
    return new VulcanConnectionService(tokens, portalUrls, authenticator, verifier, completion);
  }

  @Bean
  @ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
  VulcanSessionManager vulcanSessionManager(
      VulcanSecretStore secrets,
      VulcanBrowserAuthenticator authenticator,
      VulcanSessionVerifier verifier,
      VulcanRecoveryPersistence persistence) {
    return new VulcanSessionManager(secrets, authenticator, verifier, persistence);
  }

  private static void validateEnabled(VulcanConnectionProperties properties) {
    URI base = properties.getPublicBaseUrl();
    if (base == null
        || !base.isAbsolute()
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null
        || !("https".equalsIgnoreCase(base.getScheme()) || isLocalHttp(base))) {
      throw new IllegalStateException(
          "vulcan.connection.public-base-url must be HTTPS (HTTP is allowed only for localhost)");
    }
    Duration ttl = properties.getTokenTtl();
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalStateException("vulcan.connection.token-ttl must be positive");
    }
    if (properties.getMaxCredentialAttempts() < 1) {
      throw new IllegalStateException("vulcan.connection.max-credential-attempts must be positive");
    }
    try {
      VulcanMasterKey.fromBase64(properties.getMasterKey());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("VULCAN_MASTER_KEY is missing or invalid");
    }
  }

  private static boolean isLocalHttp(URI uri) {
    return "http".equalsIgnoreCase(uri.getScheme())
        && ("localhost".equalsIgnoreCase(uri.getHost())
            || "127.0.0.1".equals(uri.getHost())
            || "::1".equals(uri.getHost()));
  }
}

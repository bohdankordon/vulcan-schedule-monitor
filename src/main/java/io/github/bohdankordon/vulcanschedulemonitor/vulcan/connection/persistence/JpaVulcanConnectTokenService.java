package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionProperties;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectLink;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectTokenValidation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.RawConnectToken;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.TokenHashing;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.VulcanConnectLinkService;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaVulcanConnectTokenService implements VulcanConnectLinkService {

  private static final int TOKEN_BYTES = 32;

  private final VulcanConnectTokenRepository tokens;
  private final VulcanConnectionProperties properties;
  private final Clock clock;
  private final SecureRandom secureRandom = new SecureRandom();

  JpaVulcanConnectTokenService(
      VulcanConnectTokenRepository tokens, VulcanConnectionProperties properties, Clock clock) {
    this.tokens = tokens;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  @Transactional
  public ConnectLink issue(long appUserId) {
    if (!properties.isEnabled()) {
      return ConnectLink.disabled();
    }
    byte[] random = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(random);
    RawConnectToken raw =
        new RawConnectToken(Base64.getUrlEncoder().withoutPadding().encodeToString(random));
    Instant now = clock.instant();
    tokens.save(
        new VulcanConnectTokenEntity(
            appUserId, TokenHashing.sha256(raw), now, now.plus(properties.getTokenTtl())));
    URI base = properties.getPublicBaseUrl();
    String baseValue = base.toASCIIString();
    String separator = baseValue.endsWith("/") ? "" : "/";
    return ConnectLink.enabled(baseValue + separator + "connect/" + raw.value());
  }

  @Transactional(readOnly = true)
  public ConnectTokenValidation validate(RawConnectToken raw) {
    return tokens
        .findByTokenHash(TokenHashing.sha256(raw))
        .filter(token -> token.usable(clock.instant(), properties.getMaxCredentialAttempts()))
        .map(token -> ConnectTokenValidation.valid(token.appUserId()))
        .orElseGet(ConnectTokenValidation::invalid);
  }

  @Transactional
  public boolean recordInvalidCredentials(RawConnectToken raw) {
    Instant now = clock.instant();
    return tokens
        .findLockedByTokenHash(TokenHashing.sha256(raw))
        .filter(token -> token.usable(now, properties.getMaxCredentialAttempts()))
        .map(
            token -> {
              token.failedCredentialAttempt(now, properties.getMaxCredentialAttempts());
              return token.failedAttempts() < properties.getMaxCredentialAttempts();
            })
        .orElse(false);
  }
}

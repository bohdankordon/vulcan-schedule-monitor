package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.RememberedCredentials;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
public class VulcanRecoveryPersistence {

  private final VulcanAccountRepository accounts;
  private final VulcanSecretStore secrets;
  private final Clock clock;

  VulcanRecoveryPersistence(
      VulcanAccountRepository accounts, VulcanSecretStore secrets, Clock clock) {
    this.accounts = accounts;
    this.secrets = secrets;
    this.clock = clock;
  }

  @Transactional
  public void markReconnectRequired(long accountId) {
    accounts
        .findLockedById(accountId)
        .ifPresent(account -> account.reconnectRequired(clock.instant()));
  }

  @Transactional
  public void replaceRecovered(
      long accountId, VulcanSessionMaterial material, RememberedCredentials credentials) {
    Instant now = clock.instant();
    VulcanAccountEntity account =
        accounts
            .findLockedById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("VULCAN account does not exist"));
    secrets.replace(accountId, material, credentials, now);
    account.connected(credentials != null, now);
  }
}

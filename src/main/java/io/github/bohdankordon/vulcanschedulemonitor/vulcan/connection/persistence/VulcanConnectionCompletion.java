package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.RememberedCredentials;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionProperties;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.RawConnectToken;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.TokenHashing;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
public class VulcanConnectionCompletion {

  private final VulcanConnectTokenRepository tokens;
  private final VulcanAccountRepository accounts;
  private final VulcanClassCatalogRepository catalog;
  private final VulcanSecretStore secrets;
  private final VulcanConnectionProperties properties;
  private final Clock clock;

  VulcanConnectionCompletion(
      VulcanConnectTokenRepository tokens,
      VulcanAccountRepository accounts,
      VulcanClassCatalogRepository catalog,
      VulcanSecretStore secrets,
      VulcanConnectionProperties properties,
      Clock clock) {
    this.tokens = tokens;
    this.accounts = accounts;
    this.catalog = catalog;
    this.secrets = secrets;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public boolean complete(
      RawConnectToken rawToken,
      VulcanSessionMaterial session,
      RememberedCredentials rememberedCredentials,
      List<SchoolClass> discoveredClasses) {
    Instant now = clock.instant();
    VulcanConnectTokenEntity token =
        tokens
            .findLockedByTokenHash(TokenHashing.sha256(rawToken))
            .filter(candidate -> candidate.usable(now, properties.getMaxCredentialAttempts()))
            .orElse(null);
    if (token == null) {
      return false;
    }

    VulcanAccountEntity account =
        accounts
            .findByAppUserId(token.appUserId())
            .orElseGet(
                () -> accounts.saveAndFlush(new VulcanAccountEntity(token.appUserId(), now)));
    boolean remember = rememberedCredentials != null;
    account.connected(remember, now);
    secrets.replace(account.id(), session, rememberedCredentials, now);
    synchronizeCatalog(account.id(), discoveredClasses, now);
    token.consume(now);
    return true;
  }

  private void synchronizeCatalog(long accountId, List<SchoolClass> discovered, Instant now) {
    Set<Long> present = new HashSet<>();
    for (SchoolClass schoolClass : discovered) {
      if (!present.add(schoolClass.journalId())) {
        throw new IllegalArgumentException("VULCAN returned duplicate journals");
      }
      VulcanClassCatalogEntity entity =
          catalog
              .findByVulcanAccountIdAndJournalId(accountId, schoolClass.journalId())
              .orElseGet(() -> new VulcanClassCatalogEntity(accountId, schoolClass, now));
      entity.update(schoolClass, now);
      catalog.save(entity);
    }
    for (VulcanClassCatalogEntity existing : catalog.findAllByVulcanAccountId(accountId)) {
      if (!present.contains(existing.journalId())) {
        existing.deactivate(now);
      }
    }
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.VulcanRecoveryPersistence;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.util.Arrays;
import java.util.Optional;

/** Explicit recovery foundation. Phase 7 deliberately does not call this from monitoring. */
public final class VulcanSessionManager {

  public enum RecoveryResult {
    RECOVERED,
    RECONNECT_REQUIRED
  }

  private final VulcanSecretStore secrets;
  private final VulcanBrowserAuthenticator authenticator;
  private final VulcanSessionVerifier verifier;
  private final VulcanRecoveryPersistence persistence;

  public VulcanSessionManager(
      VulcanSecretStore secrets,
      VulcanBrowserAuthenticator authenticator,
      VulcanSessionVerifier verifier,
      VulcanRecoveryPersistence persistence) {
    this.secrets = secrets;
    this.authenticator = authenticator;
    this.verifier = verifier;
    this.persistence = persistence;
  }

  public VulcanSession loadCurrent(long accountId) {
    return VulcanSession.fromMaterial(secrets.loadSession(accountId));
  }

  public void replace(long accountId, VulcanSession session) {
    Optional<RememberedCredentials> credentials = secrets.loadCredentials(accountId);
    try {
      persistence.replaceRecovered(accountId, session.snapshotMaterial(), credentials.orElse(null));
    } finally {
      credentials.ifPresent(RememberedCredentials::close);
    }
  }

  public RecoveryResult recover(long accountId) {
    Optional<RememberedCredentials> optional = secrets.loadCredentials(accountId);
    if (optional.isEmpty()) {
      persistence.markReconnectRequired(accountId);
      return RecoveryResult.RECONNECT_REQUIRED;
    }
    try (RememberedCredentials credentials = optional.orElseThrow()) {
      char[] password = credentials.password();
      try (VulcanLoginRequest request =
          new VulcanLoginRequest(credentials.portalUri(), credentials.login(), password)) {
        VulcanSessionMaterial material = authenticator.authenticate(request);
        VerifiedVulcanSession verified = verifier.verifyAndDiscover(material);
        persistence.replaceRecovered(accountId, verified.sessionMaterial(), credentials);
        return RecoveryResult.RECOVERED;
      } finally {
        Arrays.fill(password, '\0');
      }
    }
  }
}

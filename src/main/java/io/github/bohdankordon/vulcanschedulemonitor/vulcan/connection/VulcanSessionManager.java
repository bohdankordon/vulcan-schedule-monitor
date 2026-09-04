package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.VulcanRecoveryPersistence;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Account-scoped encrypted session loading, rotation persistence, and serialized recovery. */
public final class VulcanSessionManager {

  public enum RecoveryResult {
    RECOVERED,
    RECONNECT_REQUIRED,
    TRANSIENT_FAILURE
  }

  private final VulcanSecretStore secrets;
  private final VulcanBrowserAuthenticator authenticator;
  private final VulcanSessionVerifier verifier;
  private final VulcanRecoveryPersistence persistence;
  private final ConcurrentMap<Long, ReentrantLock> recoveryLocks = new ConcurrentHashMap<>();

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
    ReentrantLock lock = recoveryLocks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
    lock.lock();
    try {
      return recoverLocked(accountId);
    } finally {
      lock.unlock();
    }
  }

  public void markReconnectRequired(long accountId) {
    persistence.markReconnectRequired(accountId);
  }

  private RecoveryResult recoverLocked(long accountId) {
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
      } catch (VulcanAuthenticationException exception) {
        if (exception.category() == VulcanAuthFailureCategory.TRANSIENT) {
          return RecoveryResult.TRANSIENT_FAILURE;
        }
        persistence.markReconnectRequired(accountId);
        return RecoveryResult.RECONNECT_REQUIRED;
      } finally {
        Arrays.fill(password, '\0');
      }
    }
  }
}

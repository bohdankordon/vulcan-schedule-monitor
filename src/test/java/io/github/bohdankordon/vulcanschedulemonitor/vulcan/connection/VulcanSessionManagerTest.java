package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.VulcanRecoveryPersistence;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class VulcanSessionManagerTest {

  private final VulcanSecretStore secrets = mock(VulcanSecretStore.class);
  private final VulcanBrowserAuthenticator authenticator = mock(VulcanBrowserAuthenticator.class);
  private final VulcanSessionVerifier verifier = mock(VulcanSessionVerifier.class);
  private final VulcanRecoveryPersistence persistence = mock(VulcanRecoveryPersistence.class);
  private final VulcanSessionManager manager =
      new VulcanSessionManager(secrets, authenticator, verifier, persistence);

  @Test
  void missingCredentialsRequiresReconnectWithoutLaunchingAuthenticator() {
    when(secrets.loadCredentials(41)).thenReturn(Optional.empty());

    assertThat(manager.recover(41))
        .isEqualTo(VulcanSessionManager.RecoveryResult.RECONNECT_REQUIRED);

    verify(persistence).markReconnectRequired(41);
    verify(authenticator, never()).authenticate(any());
    verify(verifier, never()).verifyAndDiscover(any());
  }

  @ParameterizedTest
  @MethodSource("terminalAuthenticationFailures")
  void invalidInteractiveAuthenticationStatesRequireManualReconnect(
      VulcanAuthFailureCategory category) {
    when(secrets.loadCredentials(41)).thenAnswer(ignored -> Optional.of(credentials()));
    when(authenticator.authenticate(any())).thenThrow(new VulcanAuthenticationException(category));

    assertThat(manager.recover(41))
        .isEqualTo(VulcanSessionManager.RecoveryResult.RECONNECT_REQUIRED);

    verify(persistence).markReconnectRequired(41);
    verify(persistence, never())
        .replaceRecovered(org.mockito.ArgumentMatchers.anyLong(), any(), any());
  }

  @Test
  void transientAuthenticationFailureKeepsAccountStateForAFutureCycle() {
    when(secrets.loadCredentials(41)).thenAnswer(ignored -> Optional.of(credentials()));
    when(authenticator.authenticate(any()))
        .thenThrow(new VulcanAuthenticationException(VulcanAuthFailureCategory.TRANSIENT));

    assertThat(manager.recover(41))
        .isEqualTo(VulcanSessionManager.RecoveryResult.TRANSIENT_FAILURE);

    verify(persistence, never()).markReconnectRequired(41);
    verify(persistence, never())
        .replaceRecovered(org.mockito.ArgumentMatchers.anyLong(), any(), any());
  }

  @Test
  void verifiedRecoveryAtomicallyPersistsTheRecoveredSessionAndCredentials() {
    VulcanSessionMaterial captured = material("captured", "sid=captured");
    VulcanSessionMaterial verifiedMaterial = material("verified", "sid=verified");
    when(secrets.loadCredentials(41)).thenAnswer(ignored -> Optional.of(credentials()));
    when(authenticator.authenticate(any())).thenReturn(captured);
    when(verifier.verifyAndDiscover(captured))
        .thenReturn(new VerifiedVulcanSession(verifiedMaterial, List.of()));

    assertThat(manager.recover(41)).isEqualTo(VulcanSessionManager.RecoveryResult.RECOVERED);

    verify(persistence)
        .replaceRecovered(
            org.mockito.ArgumentMatchers.eq(41L),
            org.mockito.ArgumentMatchers.same(verifiedMaterial),
            any());
    verify(persistence, never()).markReconnectRequired(41);
  }

  private static Stream<VulcanAuthFailureCategory> terminalAuthenticationFailures() {
    return Stream.of(
        VulcanAuthFailureCategory.INVALID_CREDENTIALS,
        VulcanAuthFailureCategory.CAPTCHA_REQUIRED,
        VulcanAuthFailureCategory.MFA_REQUIRED,
        VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW,
        VulcanAuthFailureCategory.PROTOCOL_FAILURE);
  }

  private static RememberedCredentials credentials() {
    return new RememberedCredentials(
        URI.create("https://synthetic.invalid/"),
        "synthetic-login",
        "synthetic-pass".toCharArray());
  }

  private static VulcanSessionMaterial material(String path, String cookie) {
    URI uri = URI.create("https://synthetic.invalid/" + path + "/");
    return new VulcanSessionMaterial(uri, uri, "token", "guid", cookie);
  }
}

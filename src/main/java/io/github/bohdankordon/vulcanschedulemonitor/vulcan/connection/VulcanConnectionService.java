package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.JpaVulcanConnectTokenService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.VulcanConnectionCompletion;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectTokenState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.RawConnectToken;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.util.Arrays;

public final class VulcanConnectionService {

  private final JpaVulcanConnectTokenService tokens;
  private final PortalUrlValidator portalUrls;
  private final VulcanBrowserAuthenticator authenticator;
  private final VulcanSessionVerifier verifier;
  private final VulcanConnectionCompletion completion;

  public VulcanConnectionService(
      JpaVulcanConnectTokenService tokens,
      PortalUrlValidator portalUrls,
      VulcanBrowserAuthenticator authenticator,
      VulcanSessionVerifier verifier,
      VulcanConnectionCompletion completion) {
    this.tokens = tokens;
    this.portalUrls = portalUrls;
    this.authenticator = authenticator;
    this.verifier = verifier;
    this.completion = completion;
  }

  public ConnectOutcome connect(
      RawConnectToken rawToken,
      String submittedPortalUrl,
      String login,
      char[] submittedPassword,
      boolean rememberCredentials) {
    if (tokens.validate(rawToken).state() != ConnectTokenState.VALID) {
      erase(submittedPassword);
      return ConnectOutcome.failure(ConnectOutcome.Status.TOKEN_INVALID, false);
    }

    final URI portalUri;
    try {
      portalUri = portalUrls.validate(submittedPortalUrl);
    } catch (IllegalArgumentException exception) {
      erase(submittedPassword);
      return ConnectOutcome.failure(ConnectOutcome.Status.INVALID_PORTAL, true);
    }

    try (VulcanLoginRequest loginRequest =
        new VulcanLoginRequest(portalUri, login, submittedPassword)) {
      VulcanSessionMaterial session = authenticator.authenticate(loginRequest);
      VerifiedVulcanSession verified = verifier.verifyAndDiscover(session);
      RememberedCredentials remembered =
          rememberCredentials
              ? new RememberedCredentials(portalUri, login, loginRequest.password())
              : null;
      try {
        boolean committed =
            completion.complete(
                rawToken, verified.sessionMaterial(), remembered, verified.classes());
        return committed
            ? ConnectOutcome.success(verified.classes().size())
            : ConnectOutcome.failure(ConnectOutcome.Status.TOKEN_INVALID, false);
      } finally {
        if (remembered != null) {
          remembered.close();
        }
      }
    } catch (VulcanAuthenticationException exception) {
      return authFailure(rawToken, exception.category());
    } catch (IllegalArgumentException exception) {
      return ConnectOutcome.failure(ConnectOutcome.Status.INVALID_CREDENTIALS, true);
    } finally {
      erase(submittedPassword);
    }
  }

  private ConnectOutcome authFailure(RawConnectToken token, VulcanAuthFailureCategory category) {
    return switch (category) {
      case INVALID_CREDENTIALS ->
          ConnectOutcome.failure(
              ConnectOutcome.Status.INVALID_CREDENTIALS, tokens.recordInvalidCredentials(token));
      case MFA_REQUIRED, CAPTCHA_REQUIRED, UNSUPPORTED_AUTH_FLOW ->
          ConnectOutcome.failure(ConnectOutcome.Status.UNSUPPORTED_AUTH_FLOW, true);
      case TRANSIENT -> ConnectOutcome.failure(ConnectOutcome.Status.TRANSIENT_FAILURE, true);
      case PROTOCOL_FAILURE -> ConnectOutcome.failure(ConnectOutcome.Status.PROTOCOL_FAILURE, true);
    };
  }

  private static void erase(char[] value) {
    if (value != null) {
      Arrays.fill(value, '\0');
    }
  }
}

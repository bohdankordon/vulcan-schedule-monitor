package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import java.net.URI;
import java.util.Objects;

/** Browser-free policy for the effective direct-login form submission. */
final class LoginFormSubmissionPolicy {

  private final PortalUrlValidator portalUrls;

  LoginFormSubmissionPolicy(PortalUrlValidator portalUrls) {
    this.portalUrls = Objects.requireNonNull(portalUrls, "portalUrls must not be null");
  }

  URI requireSafeSubmission(
      URI documentUri,
      boolean usernameInPasswordForm,
      boolean submitterInPasswordForm,
      String browserResolvedFormAction,
      String formMethod,
      boolean submitterOverridesAction,
      String browserResolvedSubmitterAction,
      boolean submitterOverridesMethod,
      String submitterMethod) {
    if (!usernameInPasswordForm || !submitterInPasswordForm) {
      throw unsupported();
    }

    String effectiveMethod = submitterOverridesMethod ? submitterMethod : formMethod;
    if (effectiveMethod == null || !"POST".equalsIgnoreCase(effectiveMethod.trim())) {
      throw unsupported();
    }

    String effectiveAction =
        submitterOverridesAction ? browserResolvedSubmitterAction : browserResolvedFormAction;
    try {
      URI target = documentUri.resolve(Objects.requireNonNullElse(effectiveAction, ""));
      if (!portalUrls.isAllowedRuntimeUri(target)) {
        throw unsupported();
      }
      return target;
    } catch (IllegalArgumentException exception) {
      throw unsupported();
    }
  }

  private static VulcanAuthenticationException unsupported() {
    return new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
  }
}

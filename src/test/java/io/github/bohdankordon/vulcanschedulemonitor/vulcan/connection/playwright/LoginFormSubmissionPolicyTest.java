package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class LoginFormSubmissionPolicyTest {

  private static final URI PAGE = URI.create("https://school.vulcan.net.pl/tenant/login");
  private final LoginFormSubmissionPolicy policy =
      new LoginFormSubmissionPolicy(new PortalUrlValidator());

  @Test
  void acceptsSameFormControlsWithAllowedPostTarget() {
    assertThat(requireSafe("https://school.vulcan.net.pl/tenant/submit", "POST"))
        .isEqualTo(URI.create("https://school.vulcan.net.pl/tenant/submit"));
  }

  @Test
  void acceptsRelativeAllowedVulcanPostTarget() {
    assertThat(requireSafe("submit", "post"))
        .isEqualTo(URI.create("https://school.vulcan.net.pl/tenant/submit"));
  }

  @Test
  void acceptsAbsoluteAllowedVulcanPostTarget() {
    assertThat(requireSafe("https://cufs.vulcan.net.pl/Account/LogOn", "POST"))
        .isEqualTo(URI.create("https://cufs.vulcan.net.pl/Account/LogOn"));
  }

  @Test
  void rejectsExternalHttpsFormAction() {
    assertUnsupported(() -> requireSafe("https://identity.example/login", "POST"));
  }

  @Test
  void rejectsArbitraryHostThatOnlyContainsVulcanName() {
    assertUnsupported(() -> requireSafe("https://vulcan.net.pl.attacker.example/login", "POST"));
  }

  @Test
  void rejectsGetForm() {
    assertUnsupported(() -> requireSafe("submit", "GET"));
  }

  @Test
  void rejectsJavascriptAndNonHttpTargets() {
    assertUnsupported(() -> requireSafe("javascript:synthetic", "POST"));
    assertUnsupported(() -> requireSafe("ftp://school.vulcan.net.pl/submit", "POST"));
  }

  @Test
  void rejectsExternalSubmitterActionOverride() {
    assertUnsupported(
        () ->
            policy.requireSafeSubmission(
                PAGE,
                true,
                true,
                "https://school.vulcan.net.pl/tenant/submit",
                "POST",
                true,
                "https://identity.example/collect",
                false,
                null));
  }

  @Test
  void rejectsSubmitterGetMethodOverride() {
    assertUnsupported(
        () ->
            policy.requireSafeSubmission(
                PAGE,
                true,
                true,
                "https://school.vulcan.net.pl/tenant/submit",
                "POST",
                false,
                null,
                true,
                "GET"));
  }

  @Test
  void rejectsUsernameAndPasswordFromDifferentForms() {
    assertUnsupported(
        () ->
            policy.requireSafeSubmission(
                PAGE, false, true, "submit", "POST", false, null, false, null));
  }

  @Test
  void rejectsPageWideSubmitterOutsidePasswordForm() {
    assertUnsupported(
        () ->
            policy.requireSafeSubmission(
                PAGE, true, false, "submit", "POST", false, null, false, null));
  }

  private URI requireSafe(String action, String method) {
    return policy.requireSafeSubmission(PAGE, true, true, action, method, false, null, false, null);
  }

  private static void assertUnsupported(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            exception ->
                assertThat(exception.category())
                    .isEqualTo(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW));
  }
}

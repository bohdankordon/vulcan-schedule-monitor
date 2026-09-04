package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class VulcanSessionTest {

  @Test
  void diagnosticsNeverRevealSessionMaterial() {
    VulcanSession session =
        VulcanSession.fromBrowserSession(
            URI.create("https://example.invalid/app/"),
            "synthetic-verification-value",
            "synthetic-app-value",
            "first=synthetic-cookie-value");

    assertThat(session.toString())
        .contains("[redacted]")
        .doesNotContain("synthetic-verification-value")
        .doesNotContain("synthetic-app-value")
        .doesNotContain("synthetic-cookie-value");
  }

  @Test
  void malformedCookieErrorDoesNotRevealInput() {
    assertThatThrownBy(
            () ->
                VulcanSession.fromBrowserSession(
                    URI.create("https://example.invalid/app/"),
                    "synthetic-token",
                    "synthetic-app",
                    "malformed-sensitive-value"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Browser cookie header is malformed")
        .hasMessageNotContaining("malformed-sensitive-value");
  }

  @Test
  void rejectsCrossOriginRefererBeforeSessionCanBeUsed() {
    assertThatThrownBy(
            () ->
                VulcanSession.fromBrowserSession(
                    URI.create("https://example.invalid/app/"),
                    "synthetic-token",
                    "synthetic-app",
                    "synthetic-cookie=value",
                    URI.create("https://other.invalid/page")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Referer URI must use the application origin");
  }
}

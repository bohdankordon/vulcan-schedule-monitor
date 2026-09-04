package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VulcanSessionCaptureTest {

  private final VulcanSessionCapture capture = new VulcanSessionCapture(new PortalUrlValidator());

  @Test
  void derivesApplicationPathAndPreservesUnknownSameOriginCookies() {
    URI request =
        URI.create("https://uonetplus-dziennik.vulcan.net.pl/tenant/unit/Dziennik.mvc/GetTree");
    BrowserRequestObservation observation =
        new BrowserRequestObservation(
            request,
            "https://uonetplus-dziennik.vulcan.net.pl/tenant/unit/start",
            "synthetic-verification",
            "synthetic-guid");

    VulcanSessionMaterial material =
        capture.capture(
            List.of(observation),
            List.of(
                new BrowserCookieObservation(
                    URI.create("https://uonetplus-dziennik.vulcan.net.pl/"),
                    "UnexpectedCookie",
                    "one"),
                new BrowserCookieObservation(
                    URI.create("https://uonetplus-dziennik.vulcan.net.pl/"), "FutureCookie", "two"),
                new BrowserCookieObservation(
                    URI.create("https://foreign.vulcan.net.pl/"), "Foreign", "ignored")));

    assertThat(material.applicationBaseUri())
        .isEqualTo(URI.create("https://uonetplus-dziennik.vulcan.net.pl/tenant/unit/"));
    assertThat(material.refererUri().getPath()).startsWith("/tenant/unit/");
    assertThat(material.requestVerificationToken()).isEqualTo("synthetic-verification");
    assertThat(material.appGuid()).isEqualTo("synthetic-guid");
    assertThat(material.cookieHeader())
        .contains("UnexpectedCookie=one", "FutureCookie=two")
        .doesNotContain("Foreign");
  }

  @Test
  void ignoresForeignAndIncompleteRequests() {
    BrowserRequestObservation foreign =
        new BrowserRequestObservation(
            URI.create("https://attacker.example/Dziennik.mvc/GetTree"),
            "https://attacker.example/",
            "value",
            "value");
    BrowserRequestObservation missing =
        new BrowserRequestObservation(
            URI.create("https://school.vulcan.net.pl/tenant/Dziennik.mvc/GetTree"),
            "https://school.vulcan.net.pl/tenant/",
            null,
            null);

    assertThatThrownBy(() -> capture.capture(List.of(foreign, missing), List.of()))
        .isInstanceOf(VulcanAuthenticationException.class);
  }

  @Test
  void observationRetainsOnlyRequiredHeaderValuesAndRedactsDiagnostics() {
    BrowserRequestObservation observation =
        new BrowserRequestObservation(
            URI.create("https://school.vulcan.net.pl/tenant/Dziennik.mvc/GetTree"),
            "https://school.vulcan.net.pl/tenant/",
            "synthetic-verification",
            "synthetic-guid");

    assertThat(
            Arrays.stream(BrowserRequestObservation.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
        .containsExactlyInAnyOrder("uri", "referer", "requestVerificationToken", "appGuid");
    assertThat(observation.toString())
        .doesNotContain("synthetic-verification", "synthetic-guid", "school.vulcan.net.pl");
  }
}

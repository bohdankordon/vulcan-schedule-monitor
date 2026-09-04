package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
            Map.of(
                "Referer",
                "https://uonetplus-dziennik.vulcan.net.pl/tenant/unit/start",
                "X-V-RequestVerificationToken",
                "synthetic-verification",
                "X-V-AppGuid",
                "synthetic-guid"));

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
            Map.of(
                "Referer",
                "https://attacker.example/",
                "X-V-RequestVerificationToken",
                "value",
                "X-V-AppGuid",
                "value"));
    BrowserRequestObservation missing =
        new BrowserRequestObservation(
            URI.create("https://school.vulcan.net.pl/tenant/Dziennik.mvc/GetTree"),
            Map.of("Referer", "https://school.vulcan.net.pl/tenant/"));

    assertThatThrownBy(() -> capture.capture(List.of(foreign, missing), List.of()))
        .isInstanceOf(VulcanAuthenticationException.class);
  }
}

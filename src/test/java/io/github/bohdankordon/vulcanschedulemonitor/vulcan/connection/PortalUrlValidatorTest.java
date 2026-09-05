package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PortalUrlValidatorTest {

  private final PortalUrlValidator validator = new PortalUrlValidator();

  @Test
  void acceptsOnlyStandardHttpsVulcanHosts() {
    assertThat(validator.validate("https://school.vulcan.net.pl/tenant/"))
        .isEqualTo(URI.create("https://school.vulcan.net.pl/tenant/"));
    assertThat(validator.validate("https://vulcan.net.pl/"))
        .isEqualTo(URI.create("https://vulcan.net.pl/"));
    assertThat(validator.validate("https://school.vulcan.net.pl:443/tenant"))
        .isEqualTo(URI.create("https://school.vulcan.net.pl:443/tenant"));
  }

  @Test
  void rejectsSsrfAndMalformedInputs() {
    List<String> rejected =
        List.of(
            "http://school.vulcan.net.pl/",
            "file:///etc/passwd",
            "ftp://school.vulcan.net.pl/",
            "https://localhost/",
            "https://127.0.0.1/",
            "https://[::1]/",
            "https://10.0.0.1/",
            "https://192.168.1.1/",
            "https://arbitrary.example.com/",
            "https://vulcan.net.pl.attacker.example/",
            "https://user:password@school.vulcan.net.pl/",
            "https://school.vulcan.net.pl:8443/",
            "https://school.vulcan.net.pl/path#fragment",
            "javascript:alert(1)");

    for (String candidate : rejected) {
      assertThatThrownBy(() -> validator.validate(candidate))
          .as("candidate should be rejected")
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(candidate);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://vulcan.net.pl/",
        "https://school.vulcan.net.pl:443/login?return=%2Fstart",
        "https://SCHOOL.VULCAN.NET.PL/start?view=week#schedule",
        "https://school.vulcan.net.pl/#https://external.example/"
      })
  void acceptsRuntimeQueriesAndFragmentsWithinTheHostBoundary(String url) {
    assertThat(validator.isAllowedRuntimeUri(URI.create(url))).isTrue();
  }

  @Test
  void onlyRuntimePolicyAcceptsFragmentsAndBothAcceptQueries() {
    String query = "https://school.vulcan.net.pl/login?return=%2Fstart";
    assertThat(validator.validate(query)).isEqualTo(URI.create(query));
    assertThat(validator.isAllowedRuntimeUri(URI.create(query + "#schedule"))).isTrue();
    assertThatThrownBy(() -> validator.validate(query + "#schedule"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(validator.isAllowedRuntimeUri(null)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://school.vulcan.net.pl/#schedule",
        "https://external.example/?next=https://school.vulcan.net.pl/",
        "https://vulcan.net.pl.attacker.example/#schedule",
        "https://fakevulcan.net.pl/",
        "https://127.0.0.1/",
        "https://[::1]/",
        "https://localhost/",
        "https://user:password@school.vulcan.net.pl/",
        "https://school.vulcan.net.pl:8443/",
        "file:///login",
        "javascript:alert(1)",
        "//school.vulcan.net.pl/",
        "/login"
      })
  void runtimePolicyRetainsNetworkDestinationRestrictions(String url) {
    assertThat(validator.isAllowedRuntimeUri(URI.create(url))).isFalse();
    assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(IllegalArgumentException.class);
  }
}

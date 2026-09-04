package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}

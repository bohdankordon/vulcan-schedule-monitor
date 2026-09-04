package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class VulcanConnectionConfigurationTest {

  @Test
  void disabledConfigurationRequiresNeitherPublicUrlNorMasterKey() {
    VulcanConnectionProperties properties = new VulcanConnectionProperties();

    assertThatCode(() -> new VulcanConnectionConfiguration(properties)).doesNotThrowAnyException();
  }

  @Test
  void enabledConfigurationRequiresHttpsBaseAndExactMasterKey() {
    VulcanConnectionProperties properties = new VulcanConnectionProperties();
    properties.setEnabled(true);
    properties.setPublicBaseUrl(URI.create("https://connect.example/"));
    properties.setMasterKey("short-text-password");

    assertThatThrownBy(() -> new VulcanConnectionConfiguration(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("short-text-password");
  }

  @Test
  void secretBearingPropertiesRedactDiagnostics() {
    VulcanConnectionProperties properties = new VulcanConnectionProperties();
    properties.setMasterKey("synthetic-key-marker");

    assertThat(properties.toString()).contains("[redacted]").doesNotContain("synthetic-key-marker");
  }
}

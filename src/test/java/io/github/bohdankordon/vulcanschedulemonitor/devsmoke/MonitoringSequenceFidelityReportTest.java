package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MonitoringSequenceFidelityReportTest {
  @Test
  void newFieldsAcceptOnlyBooleansBoundedCountsOrUnavailableAndNeverReflectSecrets() {
    var report = new MonitoringSequenceReport();
    String secret =
        "SUPER_SECRET_TOKEN SUPER_SECRET_APPGUID SUPER_SECRET_COOKIE SUPER_SECRET_REFERER "
            + "SECRET_NAME SECRET_TENANT_PATH SECRET_QUERY https://SECRET_DOMAIN.invalid/path";
    for (String key : MonitoringSequenceReport.FIDELITY_BOOLEANS) {
      assertThat(report.facts()).containsEntry(key, "UNAVAILABLE");
      for (Object bad : List.of(secret, 0, "true", new Object()))
        assertThatThrownBy(() -> report.put(key, bad)).hasMessage("UNSAFE_OUTPUT_GUARD");
      report.put(key, true);
      report.put(key, false);
    }
    for (String key : MonitoringSequenceReport.FIDELITY_COUNTS) {
      assertThat(report.facts()).containsEntry(key, "UNAVAILABLE");
      for (Object bad : List.of(secret, -1, 1001, 1L, 0.5, true))
        assertThatThrownBy(() -> report.put(key, bad)).hasMessage("UNSAFE_OUTPUT_GUARD");
      report.put(key, 0);
      report.put(key, 1000);
    }
    assertThatThrownBy(() -> report.put("current.liveCookieTopology.cookieNames", secret))
        .hasMessage("UNSAFE_OUTPUT_GUARD");
    assertThat(report.json())
        .doesNotContain(
            "SUPER_SECRET",
            "SECRET_NAME",
            "SECRET_TENANT_PATH",
            "SECRET_QUERY",
            "SECRET_DOMAIN",
            "https://");
  }
}

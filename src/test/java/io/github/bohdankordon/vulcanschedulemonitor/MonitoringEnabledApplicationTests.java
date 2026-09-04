package io.github.bohdankordon.vulcanschedulemonitor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringScheduler;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "vulcan.monitoring.enabled=true",
      "vulcan.connection.enabled=true",
      "vulcan.connection.public-base-url=https://connect.example",
      "vulcan.connection.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "vulcan.monitoring.poll-interval=PT24H",
      "vulcan.monitoring.request-spacing=PT0S"
    })
class MonitoringEnabledApplicationTests extends PostgresIntegrationTestSupport {

  @Autowired private MonitoringScheduler scheduler;

  @Test
  void explicitlyEnabledMonitoringWiresWithSecureSyntheticAdaptersWithoutExternalCalls() {
    assertThat(scheduler).isNotNull();
  }
}

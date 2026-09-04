package io.github.bohdankordon.vulcanschedulemonitor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringScheduler;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    properties = {
      "vulcan.monitoring.enabled=true",
      "vulcan.monitoring.poll-interval=PT24H",
      "vulcan.monitoring.request-spacing=PT0S"
    })
@Import(MonitoringEnabledApplicationTests.SyntheticMonitoringConfiguration.class)
class MonitoringEnabledApplicationTests extends PostgresIntegrationTestSupport {

  @Autowired private MonitoringScheduler scheduler;

  @Test
  void explicitlyEnabledMonitoringWiresWithSyntheticAdaptersWithoutExternalCalls() {
    assertThat(scheduler).isNotNull();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SyntheticMonitoringConfiguration {

    @Bean
    WeeklyScheduleSource weeklyScheduleSource() {
      return scope ->
          new ScheduleSnapshot(
              scope.journalId(), scope.weekStart(), scope.weekEnd(), List.of(), List.of());
    }
  }
}

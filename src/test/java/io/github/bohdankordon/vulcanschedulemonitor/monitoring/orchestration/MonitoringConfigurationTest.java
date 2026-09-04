package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MonitoringConfigurationTest {

  @Test
  void enabledMonitoringFailsClearlyWithoutRequiredApplicationAdapters() {
    new ApplicationContextRunner()
        .withUserConfiguration(MonitoringConfiguration.class)
        .withPropertyValues("vulcan.monitoring.enabled=true")
        .withBean(Clock.class, Clock::systemUTC)
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNotNull();
              assertThat(context.getStartupFailure())
                  .hasMessageContaining("resilientWeeklyScheduleSource")
                  .hasMessageContaining("WeeklyScheduleSource")
                  .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
            });
  }
}

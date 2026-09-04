package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleChangeTracker;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleRefreshCoordinator;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionProperties;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionManager;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule.PersistedAccountWeeklyScheduleSource;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({MonitoringProperties.class, VulcanConnectionProperties.class})
@ConditionalOnProperty(name = "vulcan.monitoring.enabled", havingValue = "true")
class MonitoringConfiguration {

  MonitoringConfiguration(VulcanConnectionProperties connectionProperties) {
    if (!connectionProperties.isEnabled()) {
      throw new IllegalStateException(
          "VULCAN monitoring requires secure VULCAN connection infrastructure");
    }
  }

  @Bean
  DelayStrategy monitoringDelayStrategy() {
    return new ThreadDelayStrategy();
  }

  @Bean
  MonitoringScopePlanner monitoringScopePlanner(Clock clock) {
    return new MonitoringScopePlanner(clock);
  }

  @Bean
  @ConditionalOnMissingBean(WeeklyScheduleSource.class)
  WeeklyScheduleSource persistedAccountWeeklyScheduleSource(VulcanSessionManager sessions) {
    return new PersistedAccountWeeklyScheduleSource(sessions);
  }

  @Bean
  RateLimitBackoffGate rateLimitBackoffGate(Clock clock) {
    return new RateLimitBackoffGate(clock);
  }

  @Bean
  ResilientWeeklyScheduleSource resilientWeeklyScheduleSource(
      WeeklyScheduleSource source,
      DelayStrategy monitoringDelayStrategy,
      RateLimitBackoffGate gate,
      MonitoringProperties properties) {
    return new ResilientWeeklyScheduleSource(
        source,
        monitoringDelayStrategy,
        gate,
        properties.getMaxAttempts(),
        properties.getInitialRetryBackoff(),
        properties.getFallbackRateLimitDelay(),
        properties.getMaximumInlineRateLimitDelay());
  }

  @Bean
  MonitoringCycleRunner monitoringCycleRunner(
      MonitoringTargetProvider targetProvider,
      MonitoringScopePlanner scopePlanner,
      ResilientWeeklyScheduleSource source,
      ScheduleChangeTracker tracker,
      DelayStrategy monitoringDelayStrategy,
      MonitoringProperties properties,
      Clock clock) {
    return new MonitoringCycleRunner(
        targetProvider,
        scopePlanner,
        new ScheduleRefreshCoordinator(source::fetchCompleteWeeklySnapshot, tracker),
        monitoringDelayStrategy,
        properties.getRequestSpacing(),
        clock);
  }

  @Bean
  MonitoringScheduler monitoringScheduler(MonitoringCycleRunner runner) {
    return new MonitoringScheduler(runner);
  }
}

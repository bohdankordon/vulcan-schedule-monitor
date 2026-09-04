package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleChangeTracker;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleRefreshCoordinator;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(MonitoringProperties.class)
@ConditionalOnProperty(name = "vulcan.monitoring.enabled", havingValue = "true")
class MonitoringConfiguration {

  @Bean
  DelayStrategy monitoringDelayStrategy() {
    return new ThreadDelayStrategy();
  }

  @Bean
  MonitoringScopePlanner monitoringScopePlanner(Clock clock) {
    return new MonitoringScopePlanner(clock);
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

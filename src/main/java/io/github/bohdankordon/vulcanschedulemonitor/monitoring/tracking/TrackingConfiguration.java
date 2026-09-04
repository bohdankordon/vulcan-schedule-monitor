package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TrackingConfiguration {

  @Bean
  Clock reconciliationClock() {
    return Clock.systemUTC();
  }

  @Bean
  SemanticChangeHasher semanticChangeHasher() {
    return new SemanticChangeHasher();
  }
}

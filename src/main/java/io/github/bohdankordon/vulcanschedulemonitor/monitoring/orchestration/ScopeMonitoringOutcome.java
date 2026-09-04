package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import java.time.Instant;
import java.util.Optional;

public record ScopeMonitoringOutcome(
    TrackingScope scope,
    MonitoringOutcomeCategory category,
    int activeChangeCount,
    int transitionCount,
    Instant deferredUntil) {

  public static ScopeMonitoringOutcome success(
      TrackingScope scope,
      MonitoringOutcomeCategory category,
      int activeChangeCount,
      int transitionCount) {
    return new ScopeMonitoringOutcome(scope, category, activeChangeCount, transitionCount, null);
  }

  public static ScopeMonitoringOutcome failure(
      TrackingScope scope, MonitoringOutcomeCategory category, Instant deferredUntil) {
    return new ScopeMonitoringOutcome(scope, category, 0, 0, deferredUntil);
  }

  public Optional<Instant> rateLimitDeferredUntil() {
    return Optional.ofNullable(deferredUntil);
  }
}

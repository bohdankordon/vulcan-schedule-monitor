package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.time.Instant;
import java.util.List;

public record MonitoringCycleSummary(
    Instant startedAt,
    Instant completedAt,
    int targetCount,
    int plannedScopeCount,
    boolean stoppedEarly,
    List<ScopeMonitoringOutcome> outcomes) {

  public MonitoringCycleSummary {
    outcomes = List.copyOf(outcomes);
  }

  public long successCount() {
    return outcomes.stream()
        .filter(
            outcome ->
                outcome.category() == MonitoringOutcomeCategory.SUCCESS
                    || outcome.category() == MonitoringOutcomeCategory.BASELINE_ESTABLISHED
                    || outcome.category() == MonitoringOutcomeCategory.TRANSITIONS)
        .count();
  }

  public long failureCount() {
    return outcomes.size() - successCount();
  }
}

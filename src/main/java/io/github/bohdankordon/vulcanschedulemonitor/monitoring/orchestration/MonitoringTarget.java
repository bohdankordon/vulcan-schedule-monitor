package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

public record MonitoringTarget(long journalId) implements Comparable<MonitoringTarget> {

  @Override
  public int compareTo(MonitoringTarget other) {
    return Long.compare(journalId, other.journalId);
  }
}

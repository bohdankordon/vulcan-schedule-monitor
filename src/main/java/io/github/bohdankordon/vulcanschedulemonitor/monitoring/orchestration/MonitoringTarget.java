package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

public record MonitoringTarget(long vulcanAccountId, long catalogClassId, long journalId)
    implements Comparable<MonitoringTarget> {

  public MonitoringTarget {
    if (vulcanAccountId <= 0 || catalogClassId <= 0 || journalId <= 0) {
      throw new IllegalArgumentException("Monitoring target identifiers must be positive");
    }
  }

  @Override
  public int compareTo(MonitoringTarget other) {
    int account = Long.compare(vulcanAccountId, other.vulcanAccountId);
    if (account != 0) {
      return account;
    }
    int catalog = Long.compare(catalogClassId, other.catalogClassId);
    return catalog != 0 ? catalog : Long.compare(journalId, other.journalId);
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.subscriptions;

/** Safe catalog metadata used by class-selection interfaces. */
public record MonitoringClassSelection(
    long catalogClassId, String className, String schoolUnit, int schoolYear, boolean subscribed) {

  public MonitoringClassSelection {
    if (catalogClassId <= 0) {
      throw new IllegalArgumentException("Catalog class identifier must be positive");
    }
    if (className == null || className.isBlank()) {
      throw new IllegalArgumentException("Class name must be present");
    }
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record TrackingState(
    TrackingScope scope,
    boolean baselineEstablished,
    Instant lastSuccessfulReconciliation,
    List<ActiveChangeState> activeChanges) {

  public TrackingState {
    Objects.requireNonNull(scope, "scope must not be null");
    activeChanges = List.copyOf(activeChanges);
    if (baselineEstablished && lastSuccessfulReconciliation == null) {
      throw new IllegalArgumentException("Established baseline requires a successful timestamp");
    }
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import java.util.List;

public record TrackingResult(
    boolean baselineEstablishedNow, int activeChangeCount, List<ChangeTransition> transitions) {

  public TrackingResult {
    transitions = List.copyOf(transitions);
  }
}

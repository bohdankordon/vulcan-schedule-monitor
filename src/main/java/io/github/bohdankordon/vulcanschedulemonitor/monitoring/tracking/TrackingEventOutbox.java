package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import java.time.Instant;

public interface TrackingEventOutbox {

  void recordReconciliation(TrackingScope scope, TrackingResult result, Instant occurredAt);
}

package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

public interface ActiveChangeStore {

  TrackingState lockOrCreate(TrackingScope scope);

  void save(TrackingState state);
}

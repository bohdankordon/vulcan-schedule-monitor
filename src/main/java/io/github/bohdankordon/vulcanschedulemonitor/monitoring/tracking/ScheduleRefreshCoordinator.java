package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import java.util.Objects;

public final class ScheduleRefreshCoordinator {

  private final WeeklyScheduleSource source;
  private final ScheduleChangeTracker tracker;

  public ScheduleRefreshCoordinator(WeeklyScheduleSource source, ScheduleChangeTracker tracker) {
    this.source = Objects.requireNonNull(source, "source must not be null");
    this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
  }

  public TrackingResult refreshSuccessfulWeek(TrackingScope requestedScope) {
    Objects.requireNonNull(requestedScope, "requestedScope must not be null");
    ScheduleSnapshot snapshot =
        Objects.requireNonNull(
            source.fetchCompleteWeeklySnapshot(requestedScope),
            "schedule source must return a complete snapshot or throw");
    if (!requestedScope.equals(TrackingScope.from(snapshot))) {
      throw new IllegalArgumentException("Schedule source returned a different tracking scope");
    }
    return tracker.reconcileSuccessfulSnapshot(snapshot);
  }
}

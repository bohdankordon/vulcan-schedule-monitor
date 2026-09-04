package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;

@FunctionalInterface
public interface WeeklyScheduleSource {

  ScheduleSnapshot fetchCompleteWeeklySnapshot(TrackingScope scope);
}

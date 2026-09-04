package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;

public record TrackingScope(
    long vulcanAccountId,
    long catalogClassId,
    long journalId,
    LocalDate weekStart,
    LocalDate weekEnd) {

  public TrackingScope {
    if (vulcanAccountId <= 0 || catalogClassId <= 0 || journalId <= 0) {
      throw new IllegalArgumentException("Tracking scope identifiers must be positive");
    }
    Objects.requireNonNull(weekStart, "weekStart must not be null");
    Objects.requireNonNull(weekEnd, "weekEnd must not be null");
    if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY || !weekEnd.equals(weekStart.plusDays(6))) {
      throw new IllegalArgumentException("Tracking scope must cover Monday through Sunday");
    }
  }

  public boolean matches(ScheduleSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    return journalId == snapshot.journalId()
        && weekStart.equals(snapshot.weekStart())
        && weekEnd.equals(snapshot.weekEnd());
  }
}

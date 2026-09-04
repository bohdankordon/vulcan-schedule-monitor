package io.github.bohdankordon.vulcanschedulemonitor.schedule.model;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChange;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ScheduleSnapshot(
    long journalId,
    LocalDate weekStart,
    LocalDate weekEnd,
    List<LessonOccurrence> occurrences,
    List<ScheduleChange> changes) {

  public ScheduleSnapshot {
    Objects.requireNonNull(weekStart, "weekStart must not be null");
    Objects.requireNonNull(weekEnd, "weekEnd must not be null");
    occurrences = List.copyOf(occurrences);
    changes = List.copyOf(changes);
  }
}

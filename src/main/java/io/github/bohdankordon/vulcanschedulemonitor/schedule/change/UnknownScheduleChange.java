package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record UnknownScheduleChange(LocalDate date, long lessonPeriodId, Set<ChangeSignal> signals)
    implements ScheduleChange {

  public UnknownScheduleChange {
    Objects.requireNonNull(date, "date must not be null");
    signals = Set.copyOf(signals);
    if (signals.isEmpty()) {
      throw new IllegalArgumentException("At least one change signal is required");
    }
  }
}

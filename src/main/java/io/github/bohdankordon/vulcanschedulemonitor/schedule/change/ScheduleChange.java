package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import java.time.LocalDate;

public sealed interface ScheduleChange permits TeacherSubstitution, UnknownScheduleChange {

  LocalDate date();

  long lessonPeriodId();
}

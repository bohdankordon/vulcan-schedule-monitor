package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import java.time.LocalDate;

public sealed interface ScheduleChange permits TeacherSubstitution, UnknownScheduleChange {

  LessonChangeContext context();

  default LocalDate date() {
    return context().date();
  }

  default long lessonPeriodId() {
    return context().lessonPeriodId();
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.schedule.model;

import java.time.LocalDate;
import java.util.Objects;

public record LessonOccurrence(
    LocalDate date,
    long lessonPeriodId,
    Long subjectId,
    Long teacherId,
    Long roomId,
    Long groupId) {

  public LessonOccurrence {
    Objects.requireNonNull(date, "date must not be null");
  }

  @Override
  public String toString() {
    return "LessonOccurrence[date="
        + date
        + ", lessonPeriodId="
        + lessonPeriodId
        + ", subjectId="
        + subjectId
        + ", teacherId="
        + (teacherId == null ? null : "[redacted]")
        + ", roomId="
        + roomId
        + ", groupId="
        + groupId
        + "]";
  }
}

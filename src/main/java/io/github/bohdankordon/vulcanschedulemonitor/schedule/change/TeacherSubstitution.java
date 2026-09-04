package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import java.time.LocalDate;
import java.util.Objects;

public record TeacherSubstitution(
    LocalDate date,
    long lessonPeriodId,
    String replacementTeacherCode,
    String replacementSubjectCode)
    implements ScheduleChange {

  public TeacherSubstitution {
    Objects.requireNonNull(date, "date must not be null");
    replacementTeacherCode = requireCode(replacementTeacherCode, "replacementTeacherCode");
    replacementSubjectCode = requireCode(replacementSubjectCode, "replacementSubjectCode");
  }

  @Override
  public String toString() {
    return "TeacherSubstitution[date="
        + date
        + ", lessonPeriodId="
        + lessonPeriodId
        + ", replacementTeacherCode=[redacted], replacementSubjectCode=[redacted]]";
  }

  private static String requireCode(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value.trim();
  }
}

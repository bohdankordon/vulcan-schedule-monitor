package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import java.util.Objects;

public record TeacherSubstitution(
    LessonChangeContext context, String replacementTeacherCode, String replacementSubjectCode)
    implements ScheduleChange {

  public TeacherSubstitution {
    Objects.requireNonNull(context, "context must not be null");
    replacementTeacherCode = requireCode(replacementTeacherCode, "replacementTeacherCode");
    replacementSubjectCode = requireCode(replacementSubjectCode, "replacementSubjectCode");
  }

  @Override
  public String toString() {
    return "TeacherSubstitution[context="
        + context
        + ", replacementTeacherCode=[redacted], replacementSubjectCode=[redacted]]";
  }

  private static String requireCode(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value.trim();
  }
}

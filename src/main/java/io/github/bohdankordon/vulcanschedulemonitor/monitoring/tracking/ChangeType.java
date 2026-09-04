package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.TeacherSubstitution;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.UnknownScheduleChange;

public enum ChangeType {
  TEACHER_SUBSTITUTION,
  UNKNOWN;

  static ChangeType from(ScheduleChange change) {
    if (change instanceof TeacherSubstitution) {
      return TEACHER_SUBSTITUTION;
    }
    if (change instanceof UnknownScheduleChange) {
      return UNKNOWN;
    }
    throw new IllegalArgumentException("Unsupported schedule change type");
  }
}

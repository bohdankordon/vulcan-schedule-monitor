package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import java.time.LocalDate;
import java.util.Objects;

public record ChangeMetadata(
    ChangeType changeType,
    LocalDate lessonDate,
    long lessonPeriodId,
    Long groupId,
    Long subjectId) {

  public ChangeMetadata {
    Objects.requireNonNull(changeType, "changeType must not be null");
    Objects.requireNonNull(lessonDate, "lessonDate must not be null");
  }
}

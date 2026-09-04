package io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap;

import java.time.LocalTime;
import java.util.Objects;

public record LessonPeriod(long externalId, int number, LocalTime start, LocalTime end) {

  public LessonPeriod {
    if (number < 0) {
      throw new IllegalArgumentException("Lesson period number must not be negative");
    }
    Objects.requireNonNull(start, "start must not be null");
    Objects.requireNonNull(end, "end must not be null");
  }
}

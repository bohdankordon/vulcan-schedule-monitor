package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.LessonOccurrence;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** Semantic lesson values affected by a change, without VULCAN correlation identifiers. */
public final class LessonChangeContext {

  private final LessonOccurrence planned;
  private final LessonOccurrence effective;

  private LessonChangeContext(LessonOccurrence planned, LessonOccurrence effective) {
    if (planned == null && effective == null) {
      throw new IllegalArgumentException("At least one lesson occurrence is required");
    }
    if (planned != null
        && effective != null
        && (!planned.date().equals(effective.date())
            || planned.lessonPeriodId() != effective.lessonPeriodId())) {
      throw new IllegalArgumentException(
          "Planned and effective occurrences must use the same date and lesson period");
    }
    this.planned = planned;
    this.effective = effective;
  }

  public static LessonChangeContext matched(LessonOccurrence planned, LessonOccurrence effective) {
    return new LessonChangeContext(
        Objects.requireNonNull(planned, "planned must not be null"),
        Objects.requireNonNull(effective, "effective must not be null"));
  }

  public static LessonChangeContext plannedOnly(LessonOccurrence planned) {
    return new LessonChangeContext(
        Objects.requireNonNull(planned, "planned must not be null"), null);
  }

  public static LessonChangeContext effectiveOnly(LessonOccurrence effective) {
    return new LessonChangeContext(
        null, Objects.requireNonNull(effective, "effective must not be null"));
  }

  public Optional<LessonOccurrence> planned() {
    return Optional.ofNullable(planned);
  }

  public Optional<LessonOccurrence> effective() {
    return Optional.ofNullable(effective);
  }

  public LocalDate date() {
    return selected().date();
  }

  public long lessonPeriodId() {
    return selected().lessonPeriodId();
  }

  @Override
  public boolean equals(Object candidate) {
    if (this == candidate) {
      return true;
    }
    if (!(candidate instanceof LessonChangeContext other)) {
      return false;
    }
    return Objects.equals(planned, other.planned) && Objects.equals(effective, other.effective);
  }

  @Override
  public int hashCode() {
    return Objects.hash(planned, effective);
  }

  @Override
  public String toString() {
    return "LessonChangeContext[planned=" + planned + ", effective=" + effective + "]";
  }

  private LessonOccurrence selected() {
    return effective != null ? effective : planned;
  }
}

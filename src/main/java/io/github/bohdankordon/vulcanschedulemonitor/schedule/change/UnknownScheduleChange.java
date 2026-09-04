package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record UnknownScheduleChange(
    LessonChangeContext context, Set<ChangeSignal> signals, List<String> unparsedAnnotations)
    implements ScheduleChange {

  public UnknownScheduleChange {
    Objects.requireNonNull(context, "context must not be null");
    signals = Set.copyOf(Objects.requireNonNull(signals, "signals must not be null"));
    Objects.requireNonNull(unparsedAnnotations, "unparsedAnnotations must not be null");
    unparsedAnnotations =
        unparsedAnnotations.stream()
            .map(annotation -> Objects.requireNonNull(annotation, "annotation must not be null"))
            .map(String::trim)
            .filter(annotation -> !annotation.isEmpty())
            .toList();
    if (signals.isEmpty() && unparsedAnnotations.isEmpty()) {
      throw new IllegalArgumentException("At least one change signal or annotation is required");
    }
  }

  @Override
  public String toString() {
    return "UnknownScheduleChange[context="
        + context
        + ", signals="
        + signals
        + ", unparsedAnnotations=[redacted:"
        + unparsedAnnotations.size()
        + "]]";
  }
}

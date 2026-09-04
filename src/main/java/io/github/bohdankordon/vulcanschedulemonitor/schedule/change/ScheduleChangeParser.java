package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScheduleChangeParser {

  private static final String CODE = "[\\p{L}\\p{N}._-]+";
  private static final Pattern TEACHER_SUBSTITUTION =
      Pattern.compile(
          "^\\s*zastępstwo\\s*:\\s*\\[\\s*(?<teacher>"
              + CODE
              + ")\\s*]\\s*,\\s*(?<subject>"
              + CODE
              + ")\\s*$",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  public List<ScheduleChange> parse(
      LessonChangeContext context, List<String> annotations, Set<ChangeSignal> protocolSignals) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(annotations, "annotations must not be null");
    Objects.requireNonNull(protocolSignals, "protocolSignals must not be null");
    EnumSet<ChangeSignal> signals =
        protocolSignals.isEmpty()
            ? EnumSet.noneOf(ChangeSignal.class)
            : EnumSet.copyOf(protocolSignals);
    List<ScheduleChange> changes = new ArrayList<>();
    List<String> unparsedAnnotations = new ArrayList<>();

    for (String annotation : annotations) {
      if (annotation == null || annotation.isBlank()) {
        continue;
      }
      signals.add(ChangeSignal.ANNOTATION);
      Matcher matcher = TEACHER_SUBSTITUTION.matcher(annotation);
      if (matcher.matches()) {
        changes.add(
            new TeacherSubstitution(context, matcher.group("teacher"), matcher.group("subject")));
      } else {
        unparsedAnnotations.add(annotation);
      }
    }

    if (!unparsedAnnotations.isEmpty()) {
      changes.add(new UnknownScheduleChange(context, signals, unparsedAnnotations));
    } else if (changes.isEmpty() && !signals.isEmpty()) {
      changes.add(new UnknownScheduleChange(context, signals, List.of()));
    }
    return List.copyOf(changes);
  }
}

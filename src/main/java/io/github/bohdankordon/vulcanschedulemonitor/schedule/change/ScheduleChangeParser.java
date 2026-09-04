package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
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

  public Optional<ScheduleChange> parse(
      LocalDate date, long lessonPeriodId, String annotation, Set<ChangeSignal> protocolSignals) {
    EnumSet<ChangeSignal> signals =
        protocolSignals.isEmpty()
            ? EnumSet.noneOf(ChangeSignal.class)
            : EnumSet.copyOf(protocolSignals);

    if (annotation != null && !annotation.isBlank()) {
      signals.add(ChangeSignal.ANNOTATION);
      Matcher matcher = TEACHER_SUBSTITUTION.matcher(annotation);
      if (matcher.matches()) {
        return Optional.of(
            new TeacherSubstitution(
                date, lessonPeriodId, matcher.group("teacher"), matcher.group("subject")));
      }
    }

    if (signals.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new UnknownScheduleChange(date, lessonPeriodId, signals));
  }
}

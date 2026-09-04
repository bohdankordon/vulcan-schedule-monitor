package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScheduleChangeParserTest {

  private static final LocalDate DATE = LocalDate.of(2099, 9, 8);
  private final ScheduleChangeParser parser = new ScheduleChangeParser();

  @Test
  void parsesKnownTeacherSubstitution() {
    assertThat(parser.parse(DATE, 501, "zastępstwo: [T1], SUBJ_A", Set.of()))
        .contains(new TeacherSubstitution(DATE, 501, "T1", "SUBJ_A"));
  }

  @Test
  void acceptsWhitespaceAroundKnownSubstitutionParts() {
    assertThat(parser.parse(DATE, 501, "  zastępstwo : [ T2 ] , SUBJECT_2  ", Set.of()))
        .contains(new TeacherSubstitution(DATE, 501, "T2", "SUBJECT_2"));
  }

  @Test
  void preservesUnrecognizedAnnotationAsUnknownChange() {
    assertThat(parser.parse(DATE, 501, "synthetic new annotation", Set.of()))
        .containsInstanceOf(UnknownScheduleChange.class)
        .get()
        .extracting(ScheduleChange::date, ScheduleChange::lessonPeriodId)
        .containsExactly(DATE, 501L);
  }
}

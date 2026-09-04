package io.github.bohdankordon.vulcanschedulemonitor.schedule.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.LessonOccurrence;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScheduleChangeParserTest {

  private static final LocalDate DATE = LocalDate.of(2099, 9, 8);
  private static final LessonOccurrence OCCURRENCE =
      new LessonOccurrence(DATE, 501, 9001L, 9101L, 9201L, 8001L);
  private static final LessonChangeContext CONTEXT =
      LessonChangeContext.matched(OCCURRENCE, OCCURRENCE);
  private final ScheduleChangeParser parser = new ScheduleChangeParser();

  @Test
  void emptyAnnotationsAndSignalsProduceNoChange() {
    assertThat(parser.parse(CONTEXT, List.of(), Set.of())).isEmpty();
  }

  @Test
  void parsesKnownTeacherSubstitution() {
    assertThat(parser.parse(CONTEXT, List.of("zastępstwo: [T1], SUBJ_A"), Set.of()))
        .containsExactly(new TeacherSubstitution(CONTEXT, "T1", "SUBJ_A"));
  }

  @Test
  void acceptsWhitespaceAroundKnownSubstitutionParts() {
    assertThat(parser.parse(CONTEXT, List.of("  zastępstwo : [ T2 ] , SUBJECT_2  "), Set.of()))
        .containsExactly(new TeacherSubstitution(CONTEXT, "T2", "SUBJECT_2"));
  }

  @Test
  void preservesUnknownAnnotationButRedactsItFromDiagnostics() {
    List<ScheduleChange> changes =
        parser.parse(CONTEXT, List.of("synthetic new annotation"), Set.of());

    assertThat(changes).singleElement().isInstanceOf(UnknownScheduleChange.class);
    UnknownScheduleChange unknown = (UnknownScheduleChange) changes.getFirst();
    assertThat(unknown.unparsedAnnotations()).containsExactly("synthetic new annotation");
    assertThat(unknown.signals()).containsExactly(ChangeSignal.ANNOTATION);
    assertThat(unknown.toString())
        .contains("unparsedAnnotations=[redacted:1]")
        .doesNotContain("synthetic new annotation")
        .doesNotContain("9101");
  }

  @Test
  void handlesKnownAndMultipleUnknownAnnotationsWithoutDataLoss() {
    List<String> annotations =
        new ArrayList<>(
            List.of(
                "zastępstwo: [T3], SUBJECT_3",
                "first synthetic unknown",
                "second synthetic unknown"));
    List<ScheduleChange> changes = parser.parse(CONTEXT, annotations, Set.of(ChangeSignal.BOLDED));
    annotations.clear();

    assertThat(changes).hasSize(2);
    assertThat(changes.getFirst()).isEqualTo(new TeacherSubstitution(CONTEXT, "T3", "SUBJECT_3"));
    List<String> retained = ((UnknownScheduleChange) changes.getLast()).unparsedAnnotations();
    assertThat(retained).containsExactly("first synthetic unknown", "second synthetic unknown");
    assertThatThrownBy(() -> retained.add("mutation"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void understoodSubstitutionDoesNotCreateAnotherUnknownForPresentationMarkers() {
    assertThat(
            parser.parse(
                CONTEXT, List.of("zastępstwo: [T4], SUBJECT_4"), Set.of(ChangeSignal.BOLDED)))
        .containsExactly(new TeacherSubstitution(CONTEXT, "T4", "SUBJECT_4"));
  }

  @Test
  void markerOnlyChangeIsPreservedWithoutInventingAnnotationContent() {
    List<ScheduleChange> changes = parser.parse(CONTEXT, List.of(), Set.of(ChangeSignal.STRIKED));

    assertThat(changes).singleElement().isInstanceOf(UnknownScheduleChange.class);
    assertThat(((UnknownScheduleChange) changes.getFirst()).unparsedAnnotations()).isEmpty();
  }
}

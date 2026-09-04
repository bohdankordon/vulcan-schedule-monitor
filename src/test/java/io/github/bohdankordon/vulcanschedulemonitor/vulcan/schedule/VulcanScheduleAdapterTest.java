package io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule;

import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ChangeSignal;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.LessonChangeContext;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.TeacherSubstitution;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.UnknownScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.LessonOccurrence;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class VulcanScheduleAdapterTest {

  private static final LocalDate WEEK_START = LocalDate.of(2099, 9, 7);
  private static final LocalDate WEEK_END = LocalDate.of(2099, 9, 13);

  @Test
  void preservesSameSlotMultiplicityAndNullableFieldsWithoutInventingChanges() {
    ScheduleSnapshot snapshot =
        adapter().mapResponse(4201, WEEK_START, WEEK_END, json("schedule-normal"));

    assertThat(snapshot.occurrences()).hasSize(2);
    assertThat(snapshot.occurrences())
        .allMatch(occurrence -> occurrence.date().equals(WEEK_START))
        .allMatch(occurrence -> occurrence.lessonPeriodId() == 501);
    assertThat(snapshot.occurrences().get(1).teacherId()).isNull();
    assertThat(snapshot.occurrences().get(1).roomId()).isNull();
    assertThat(snapshot.occurrences().get(1).groupId()).isNull();
    assertThat(snapshot.changes()).isEmpty();
  }

  @Test
  void extractsKnownSubstitutionEvenWhenCoreIdentifiersAreUnchanged() {
    ScheduleSnapshot snapshot =
        adapter().mapResponse(4201, WEEK_START, WEEK_END, json("schedule-substitution"));
    LessonOccurrence occurrence =
        new LessonOccurrence(LocalDate.of(2099, 9, 8), 850, 9003L, 9103L, 9203L, null);
    LessonChangeContext expectedContext = LessonChangeContext.matched(occurrence, occurrence);

    assertThat(snapshot.changes())
        .containsExactly(new TeacherSubstitution(expectedContext, "T1", "SUBJ_A"));
    TeacherSubstitution substitution = (TeacherSubstitution) snapshot.changes().getFirst();
    assertThat(substitution.context().planned()).contains(occurrence);
    assertThat(substitution.context().effective()).contains(occurrence);
    assertThat(substitution.toString()).doesNotContain("T1", "SUBJ_A", "9103", "7201", "7301");
    assertThat(occurrence.toString()).contains("teacherId=[redacted]").doesNotContain("9103");
    assertThat(snapshot.toString()).doesNotContain("T1", "SUBJ_A", "9103", "7201", "7301");
  }

  @Test
  void preservesUnknownAnnotationsAndDistinguishesChangesInTheSameSlot() {
    ScheduleSnapshot snapshot =
        adapter().mapResponse(4201, WEEK_START, WEEK_END, json("schedule-unknown-change"));

    assertThat(snapshot.changes()).hasSize(2).allMatch(UnknownScheduleChange.class::isInstance);
    UnknownScheduleChange annotationChange = (UnknownScheduleChange) snapshot.changes().get(0);
    UnknownScheduleChange markerChange = (UnknownScheduleChange) snapshot.changes().get(1);
    assertThat(annotationChange.signals()).contains(ChangeSignal.ANNOTATION);
    assertThat(annotationChange.unparsedAnnotations())
        .containsExactly("synthetic unknown annotation", "second synthetic unknown annotation");
    assertThat(annotationChange.toString())
        .doesNotContain("synthetic unknown annotation", "second synthetic unknown annotation");
    assertThat(markerChange.signals()).contains(ChangeSignal.BASE_MARKER);
    assertThat(markerChange.unparsedAnnotations()).isEmpty();
    assertThat(annotationChange.date()).isEqualTo(markerChange.date());
    assertThat(annotationChange.lessonPeriodId()).isEqualTo(markerChange.lessonPeriodId());
    assertThat(annotationChange.context().effective().orElseThrow().groupId()).isEqualTo(8101L);
    assertThat(markerChange.context().effective().orElseThrow().groupId()).isEqualTo(8102L);
    assertThat(annotationChange.context().effective().orElseThrow().subjectId()).isEqualTo(9004L);
    assertThat(markerChange.context().effective().orElseThrow().subjectId()).isEqualTo(9005L);
    assertThat(annotationChange.context()).isNotEqualTo(markerChange.context());
  }

  @Test
  void representsUnmatchedEffectiveAndBaseOnlyMarkedEntriesWithoutFakeOccurrences() {
    ScheduleSnapshot snapshot =
        adapter().mapResponse(4201, WEEK_START, WEEK_END, json("schedule-unmatched"));

    assertThat(snapshot.changes()).hasSize(2).allMatch(UnknownScheduleChange.class::isInstance);
    UnknownScheduleChange unmatchedEffective = (UnknownScheduleChange) snapshot.changes().get(0);
    UnknownScheduleChange baseOnly = (UnknownScheduleChange) snapshot.changes().get(1);
    assertThat(unmatchedEffective.signals())
        .contains(ChangeSignal.UNMATCHED_ENTRY, ChangeSignal.BOLDED);
    assertThat(unmatchedEffective.context().planned()).isEmpty();
    assertThat(unmatchedEffective.context().effective()).isPresent();
    assertThat(baseOnly.signals()).containsExactly(ChangeSignal.BASE_MARKER);
    assertThat(baseOnly.context().planned()).isPresent();
    assertThat(baseOnly.context().effective()).isEmpty();
    assertThat(snapshot.changes().toString()).doesNotContain("7601", "7701");
  }

  @Test
  void rejectsTheFormerScalarAnnotationShape() {
    JsonNode response = json("schedule-normal");
    ObjectNode effectiveRow = (ObjectNode) response.get("data").get("planLekcjiZeZmianami").get(0);
    effectiveRow.put("ChangeAnnotation", "synthetic scalar annotation");

    assertThatThrownBy(() -> adapter().mapResponse(4201, WEEK_START, WEEK_END, response))
        .isInstanceOf(VulcanProtocolException.class)
        .hasMessageNotContaining("synthetic scalar annotation");
  }

  private static VulcanScheduleAdapter adapter() {
    VulcanSession session =
        VulcanSession.fromBrowserSession(
            URI.create("https://example.invalid/app/"),
            "synthetic-token",
            "synthetic-app",
            "synthetic-cookie=value");
    return new VulcanScheduleAdapter(
        session, new VulcanHttpTransport(session, Duration.ofSeconds(1), Duration.ofSeconds(1)));
  }
}

package io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule;

import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ChangeSignal;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.TeacherSubstitution;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.UnknownScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

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

    assertThat(snapshot.changes())
        .containsExactly(new TeacherSubstitution(LocalDate.of(2099, 9, 8), 850, "T1", "SUBJ_A"));
  }

  @Test
  void preservesUnknownAnnotationAndMarkerOnlyChanges() {
    ScheduleSnapshot snapshot =
        adapter().mapResponse(4201, WEEK_START, WEEK_END, json("schedule-unknown-change"));

    assertThat(snapshot.changes()).hasSize(2).allMatch(UnknownScheduleChange.class::isInstance);
    UnknownScheduleChange annotationChange = (UnknownScheduleChange) snapshot.changes().get(0);
    UnknownScheduleChange markerChange = (UnknownScheduleChange) snapshot.changes().get(1);
    assertThat(annotationChange.signals()).contains(ChangeSignal.ANNOTATION);
    assertThat(markerChange.signals()).contains(ChangeSignal.BASE_MARKER);
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

package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ChangeSignal;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.LessonChangeContext;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.TeacherSubstitution;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.UnknownScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.LessonOccurrence;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticChangeHasherTest {

  private static final long JOURNAL_ID = 42L;
  private static final LocalDate DATE = LocalDate.of(2026, 9, 8);

  private final SemanticChangeHasher hasher = new SemanticChangeHasher();

  @Test
  void sameChangeAndJournalProduceStableIdentityAndFingerprint() {
    ScheduleChange change = substitution(occurrence(10L, 20L, 30L, 40L), "T2", "S2");

    assertThat(hasher.changeKey(JOURNAL_ID, change))
        .isEqualTo(hasher.changeKey(JOURNAL_ID, change));
    assertThat(hasher.fingerprint(change)).isEqualTo(hasher.fingerprint(change));
  }

  @Test
  void replacementValuesChangeFingerprintButNotLogicalKey() {
    LessonOccurrence planned = occurrence(10L, 20L, 30L, 40L);
    ScheduleChange first = substitution(planned, "T2", "S2");
    ScheduleChange updated = substitution(planned, "T3", "S3");

    assertThat(hasher.changeKey(JOURNAL_ID, first))
        .isEqualTo(hasher.changeKey(JOURNAL_ID, updated));
    assertThat(hasher.fingerprint(first)).isNotEqualTo(hasher.fingerprint(updated));
  }

  @Test
  void changeTypeParticipatesInLogicalKey() {
    LessonOccurrence occurrence = occurrence(10L, 20L, 30L, 40L);
    ScheduleChange substitution = substitution(occurrence, "T2", "S2");
    ScheduleChange unknown = unknown(occurrence, Set.of(ChangeSignal.BOLDED), List.of());

    assertThat(hasher.changeKey(JOURNAL_ID, substitution))
        .isNotEqualTo(hasher.changeKey(JOURNAL_ID, unknown));
  }

  @Test
  void groupParticipatesInLogicalKey() {
    ScheduleChange first = substitution(occurrence(10L, 20L, 30L, 40L), "T2", "S2");
    ScheduleChange second = substitution(occurrence(10L, 20L, 30L, 41L), "T2", "S2");

    assertThat(hasher.changeKey(JOURNAL_ID, first))
        .isNotEqualTo(hasher.changeKey(JOURNAL_ID, second));
  }

  @Test
  void subjectParticipatesInLogicalKey() {
    ScheduleChange first = substitution(occurrence(10L, 20L, 30L, 40L), "T2", "S2");
    ScheduleChange second = substitution(occurrence(11L, 20L, 30L, 40L), "T2", "S2");

    assertThat(hasher.changeKey(JOURNAL_ID, first))
        .isNotEqualTo(hasher.changeKey(JOURNAL_ID, second));
  }

  @Test
  void teacherAndRoomParticipateDeterministicallyInLogicalKey() {
    ScheduleChange base = substitution(occurrence(10L, 20L, 30L, 40L), "T2", "S2");
    ScheduleChange differentTeacher = substitution(occurrence(10L, 21L, 30L, 40L), "T2", "S2");
    ScheduleChange differentRoom = substitution(occurrence(10L, 20L, 31L, 40L), "T2", "S2");

    assertThat(hasher.changeKey(JOURNAL_ID, base))
        .isNotEqualTo(hasher.changeKey(JOURNAL_ID, differentTeacher))
        .isNotEqualTo(hasher.changeKey(JOURNAL_ID, differentRoom));
  }

  @Test
  void unknownAnnotationContentChangesFingerprintButNotLogicalKey() {
    LessonOccurrence occurrence = occurrence(10L, 20L, 30L, 40L);
    ScheduleChange first = unknown(occurrence, Set.of(), List.of("first annotation"));
    ScheduleChange changed = unknown(occurrence, Set.of(), List.of("changed annotation"));

    assertThat(hasher.changeKey(JOURNAL_ID, first))
        .isEqualTo(hasher.changeKey(JOURNAL_ID, changed));
    assertThat(hasher.fingerprint(first)).isNotEqualTo(hasher.fingerprint(changed));
  }

  @Test
  void changeSignalSetIterationOrderDoesNotAffectFingerprint() {
    LessonOccurrence occurrence = occurrence(10L, 20L, 30L, 40L);
    var forward = new LinkedHashSet<>(List.of(ChangeSignal.BOLDED, ChangeSignal.STRIKED));
    var reverse = new LinkedHashSet<>(List.of(ChangeSignal.STRIKED, ChangeSignal.BOLDED));

    assertThat(hasher.fingerprint(unknown(occurrence, forward, List.of())))
        .isEqualTo(hasher.fingerprint(unknown(occurrence, reverse, List.of())));
  }

  @Test
  void unknownAnnotationObservedOrderParticipatesInFingerprint() {
    LessonOccurrence occurrence = occurrence(10L, 20L, 30L, 40L);
    ScheduleChange first = unknown(occurrence, Set.of(), List.of("alpha", "beta"));
    ScheduleChange sameOrder = unknown(occurrence, Set.of(), List.of("alpha", "beta"));
    ScheduleChange reversed = unknown(occurrence, Set.of(), List.of("beta", "alpha"));

    assertThat(hasher.fingerprint(first)).isEqualTo(hasher.fingerprint(sameOrder));
    assertThat(hasher.fingerprint(first)).isNotEqualTo(hasher.fingerprint(reversed));
  }

  @Test
  void hashesAreLowercaseHexEncodedSha256Values() {
    ScheduleChange change = substitution(occurrence(10L, 20L, 30L, 40L), "T2", "S2");

    assertThat(hasher.changeKey(JOURNAL_ID, change)).matches("[0-9a-f]{64}");
    assertThat(hasher.fingerprint(change)).matches("[0-9a-f]{64}");
  }

  @Test
  void publicHashingApiAcceptsOnlyJournalAndProtocolIndependentChange() {
    assertThat(SemanticChangeHasher.class.getDeclaredMethods())
        .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
        .extracting(method -> List.of(method.getParameterTypes()))
        .containsExactlyInAnyOrder(
            List.of(long.class, ScheduleChange.class), List.of(ScheduleChange.class));
  }

  private static TeacherSubstitution substitution(
      LessonOccurrence planned, String teacherCode, String subjectCode) {
    LessonOccurrence effective =
        new LessonOccurrence(
            planned.date(),
            planned.lessonPeriodId(),
            planned.subjectId(),
            planned.teacherId(),
            planned.roomId(),
            planned.groupId());
    return new TeacherSubstitution(
        LessonChangeContext.matched(planned, effective), teacherCode, subjectCode);
  }

  private static UnknownScheduleChange unknown(
      LessonOccurrence occurrence, Set<ChangeSignal> signals, List<String> annotations) {
    return new UnknownScheduleChange(
        LessonChangeContext.plannedOnly(occurrence), signals, annotations);
  }

  private static LessonOccurrence occurrence(
      Long subjectId, Long teacherId, Long roomId, Long groupId) {
    return new LessonOccurrence(DATE, 3L, subjectId, teacherId, roomId, groupId);
  }
}

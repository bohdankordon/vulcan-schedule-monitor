package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringCycleRunner;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringOutcomeCategory;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringScopePlanner;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTarget;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.RateLimitBackoffGate;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.ResilientWeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.ScopeMonitoringOutcome;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.LessonChangeContext;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.TeacherSubstitution;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.LessonOccurrence;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(ScheduleChangeTrackerPostgresTests.TestClockConfiguration.class)
class ScheduleChangeTrackerPostgresTests extends PostgresIntegrationTestSupport {

  private static final long JOURNAL_ID = 42L;
  private static final LocalDate WEEK_START = LocalDate.of(2026, 9, 7);
  private static final LocalDate WEEK_END = WEEK_START.plusDays(6);
  private static final Instant FIRST_FETCH = Instant.parse("2026-09-04T08:00:00Z");
  private static final Instant SECOND_FETCH = Instant.parse("2026-09-04T09:00:00Z");
  private static final Instant THIRD_FETCH = Instant.parse("2026-09-04T10:00:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ScheduleChangeTracker tracker;
  @Autowired private MutableClock clock;
  @Autowired private PersistenceRollbackProbe rollbackProbe;

  private final SemanticChangeHasher hasher = new SemanticChangeHasher();

  @BeforeEach
  void clearDatabase() {
    jdbc.update("DELETE FROM tracking_scope");
  }

  @Test
  void emptySuccessfulSnapshotEstablishesBaseline() {
    TrackingResult result = trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot());

    assertThat(result.baselineEstablishedNow()).isTrue();
    assertThat(result.activeChangeCount()).isZero();
    assertThat(result.transitions()).isEmpty();
    assertThat(
            jdbc.queryForObject("SELECT baseline_established FROM tracking_scope", Boolean.class))
        .isTrue();
    assertThat(timestamp("SELECT last_success_at FROM tracking_scope")).isEqualTo(FIRST_FETCH);
  }

  @Test
  void existingChangesEstablishBaselineWithoutNewTransitions() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");

    TrackingResult result = trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(existing));

    assertThat(result.baselineEstablishedNow()).isTrue();
    assertThat(result.activeChangeCount()).isOne();
    assertThat(result.transitions()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
  }

  @Test
  void unchangedChangeUpdatesLastSeenAndPreservesFirstSeen() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(existing));

    TrackingResult result = trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(snapshot(existing));

    assertThat(result.baselineEstablishedNow()).isFalse();
    assertThat(result.transitions()).isEmpty();
    assertThat(timestamp("SELECT first_seen_at FROM schedule_change_state")).isEqualTo(FIRST_FETCH);
    assertThat(timestamp("SELECT last_seen_at FROM schedule_change_state")).isEqualTo(SECOND_FETCH);
  }

  @Test
  void newlyAppearingChangeIsPersistedAndEmitsNew() {
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot());
    ScheduleChange appeared = substitution(10L, 20L, 30L, 40L, "T2", "S2");

    TrackingResult result = trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(snapshot(appeared));

    assertThat(result.transitions())
        .singleElement()
        .satisfies(
            transition -> {
              assertThat(transition.lifecycle()).isEqualTo(ChangeLifecycle.NEW);
              assertThat(transition.currentChange()).contains(appeared);
            });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
  }

  @Test
  void changedContentUpdatesFingerprintWithoutDuplicatingActiveState() {
    ScheduleChange original = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    ScheduleChange updated = substitution(10L, 20L, 30L, 40L, "T3", "S3");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(original));

    TrackingResult result = trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(snapshot(updated));

    assertThat(result.transitions())
        .singleElement()
        .satisfies(
            transition -> assertThat(transition.lifecycle()).isEqualTo(ChangeLifecycle.UPDATED));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
    assertThat(jdbc.queryForObject("SELECT fingerprint FROM schedule_change_state", String.class))
        .isEqualTo(hasher.fingerprint(updated));
    assertThat(timestamp("SELECT first_seen_at FROM schedule_change_state")).isEqualTo(FIRST_FETCH);
  }

  @Test
  void absentChangeResolvesIsRemovedAndReappearanceBecomesNew() {
    ScheduleChange change = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(change));

    TrackingResult resolved = trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(snapshot());

    assertThat(resolved.transitions())
        .singleElement()
        .satisfies(
            transition -> {
              assertThat(transition.lifecycle()).isEqualTo(ChangeLifecycle.RESOLVED);
              assertThat(transition.currentChange()).isEmpty();
              assertThat(transition.metadata().groupId()).isEqualTo(40L);
            });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isZero();

    TrackingResult reappeared =
        trackerAt(THIRD_FETCH).reconcileSuccessfulSnapshot(snapshot(change));
    assertThat(reappeared.transitions())
        .extracting(ChangeTransition::lifecycle)
        .containsExactly(ChangeLifecycle.NEW);
    assertThat(timestamp("SELECT first_seen_at FROM schedule_change_state")).isEqualTo(THIRD_FETCH);
  }

  @Test
  void sameSlotDifferentGroupsRemainIndependent() {
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot());
    ScheduleChange firstGroup = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    ScheduleChange secondGroup = substitution(10L, 20L, 30L, 41L, "T2", "S2");

    TrackingResult result =
        trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(snapshot(firstGroup, secondGroup));

    assertThat(result.transitions())
        .hasSize(2)
        .allSatisfy(
            transition -> assertThat(transition.lifecycle()).isEqualTo(ChangeLifecycle.NEW));
    assertThat(
            jdbc.queryForList(
                "SELECT group_id FROM schedule_change_state ORDER BY group_id", Long.class))
        .containsExactly(40L, 41L);
  }

  @Test
  void multipleIndependentChangesReconcileTogether() {
    ScheduleChange unchanged = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    ScheduleChange beforeUpdate = substitution(10L, 20L, 30L, 41L, "T2", "S2");
    ScheduleChange resolved = substitution(10L, 20L, 30L, 42L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(unchanged, beforeUpdate, resolved));

    ScheduleChange updated = substitution(10L, 20L, 30L, 41L, "T3", "S3");
    ScheduleChange appeared = substitution(10L, 20L, 30L, 43L, "T2", "S2");
    TrackingResult result =
        trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(snapshot(unchanged, updated, appeared));

    assertThat(result.transitions())
        .extracting(ChangeTransition::lifecycle)
        .containsExactly(ChangeLifecycle.UPDATED, ChangeLifecycle.NEW, ChangeLifecycle.RESOLVED);
    assertThat(
            jdbc.queryForList(
                "SELECT group_id FROM schedule_change_state ORDER BY group_id", Long.class))
        .containsExactly(40L, 41L, 43L);
    assertThat(timestamp("SELECT last_success_at FROM tracking_scope")).isEqualTo(SECOND_FETCH);
  }

  @Test
  void duplicateSemanticInputFailsBeforeMutationAndPreservesExistingState() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(existing));
    Map<String, Object> before = databaseState();

    assertThatThrownBy(
            () -> trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(snapshot(existing, existing)))
        .isInstanceOf(DuplicateSemanticChangeKeyException.class)
        .hasMessageContaining("duplicate semantic change key")
        .hasMessageNotContaining("T2")
        .hasMessageNotContaining("S2");

    assertThat(databaseState()).isEqualTo(before);
  }

  @Test
  void persistenceFailureAfterFlushedReplacementWorkRollsBackEntireTransaction() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(existing));
    Map<String, Object> before = databaseState();

    assertThatThrownBy(() -> rollbackProbe.replaceWithInvalidFingerprint(scope(), SECOND_FETCH))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(databaseState()).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM schedule_change_state WHERE length(fingerprint) > 64",
                Integer.class))
        .isZero();
  }

  @Test
  void failedFetchNeverInvokesSuccessfulReconciliationOrResolvesActiveState() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(existing));
    Map<String, Object> before = databaseState();
    ScheduleRefreshCoordinator coordinator =
        new ScheduleRefreshCoordinator(
            ignored -> {
              throw new IllegalStateException("synthetic fetch failure");
            },
            trackerAt(SECOND_FETCH));

    assertThatThrownBy(() -> coordinator.refreshSuccessfulWeek(scope()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("synthetic fetch failure");

    assertThat(databaseState()).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
  }

  @Test
  void exhaustedScheduledRefreshLeavesPostgresStateUnchangedAndEmitsNoResolvedTransition() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(existing));
    Map<String, Object> before = databaseState();
    Clock cycleClock = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC);
    WeeklyScheduleSource failingSource =
        ignored -> {
          throw VulcanHttpException.transportFailure("schedule");
        };
    ResilientWeeklyScheduleSource resilientSource =
        new ResilientWeeklyScheduleSource(
            failingSource,
            ignored -> {},
            new RateLimitBackoffGate(cycleClock),
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));
    MonitoringCycleRunner runner =
        new MonitoringCycleRunner(
            () -> List.of(new MonitoringTarget(JOURNAL_ID)),
            new MonitoringScopePlanner(cycleClock),
            new ScheduleRefreshCoordinator(
                resilientSource::fetchCompleteWeeklySnapshot, trackerAt(SECOND_FETCH)),
            ignored -> {},
            Duration.ZERO,
            cycleClock);

    var summary = runner.runCycle();

    assertThat(summary.outcomes())
        .extracting(ScopeMonitoringOutcome::category)
        .containsOnly(MonitoringOutcomeCategory.TRANSIENT_FAILURE_EXHAUSTED);
    assertThat(summary.outcomes())
        .extracting(ScopeMonitoringOutcome::transitionCount)
        .containsOnly(0);
    assertThat(databaseState()).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
  }

  @Test
  void databaseEnforcesUniqueScopeAndActiveChangeKeys() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(existing));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO tracking_scope
                      (journal_id, week_start, week_end, baseline_established, last_success_at)
                    VALUES (?, ?, ?, TRUE, ?)
                    """,
                    JOURNAL_ID,
                    WEEK_START,
                    WEEK_END,
                    Timestamp.from(FIRST_FETCH)))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO schedule_change_state
                      (scope_id, change_key, fingerprint, change_type, lesson_date,
                       lesson_period_id, group_id, subject_id, first_seen_at, last_seen_at)
                    SELECT scope_id, change_key, fingerprint, change_type, lesson_date,
                           lesson_period_id, group_id, subject_id, first_seen_at, last_seen_at
                    FROM schedule_change_state
                    """))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseEnforcesBaselineSuccessTimestampInvariant() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO tracking_scope
                      (journal_id, week_start, week_end, baseline_established, last_success_at)
                    VALUES (?, ?, ?, TRUE, NULL)
                    """,
                    1001L,
                    WEEK_START,
                    WEEK_END))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO tracking_scope
                      (journal_id, week_start, week_end, baseline_established, last_success_at)
                    VALUES (?, ?, ?, FALSE, ?)
                    """,
                    1002L,
                    WEEK_START,
                    WEEK_END,
                    Timestamp.from(FIRST_FETCH)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void persistedStateContainsOnlyMinimizedProtocolNeutralMetadata() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "PRIVATE-TEACHER", "SUBJECT");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(snapshot(existing));

    List<String> columns =
        jdbc.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'schedule_change_state'
            ORDER BY ordinal_position
            """,
            String.class);
    assertThat(columns)
        .containsExactly(
            "id",
            "scope_id",
            "change_key",
            "fingerprint",
            "change_type",
            "lesson_date",
            "lesson_period_id",
            "group_id",
            "subject_id",
            "first_seen_at",
            "last_seen_at")
        .noneMatch(name -> name.contains("annotation") || name.contains("teacher"));
  }

  private ScheduleChangeTracker trackerAt(Instant instant) {
    clock.setInstant(instant);
    return tracker;
  }

  private Instant timestamp(String sql) {
    return jdbc.queryForObject(sql, Timestamp.class).toInstant();
  }

  private Map<String, Object> databaseState() {
    return jdbc.queryForMap(
        """
        SELECT scope.baseline_established, scope.last_success_at, state.change_key,
               state.fingerprint, state.first_seen_at, state.last_seen_at
        FROM tracking_scope scope
        JOIN schedule_change_state state ON state.scope_id = scope.id
        """);
  }

  private static TrackingScope scope() {
    return new TrackingScope(JOURNAL_ID, WEEK_START, WEEK_END);
  }

  private static ScheduleSnapshot snapshot(ScheduleChange... changes) {
    return new ScheduleSnapshot(JOURNAL_ID, WEEK_START, WEEK_END, List.of(), List.of(changes));
  }

  private static TeacherSubstitution substitution(
      Long subjectId,
      Long teacherId,
      Long roomId,
      Long groupId,
      String replacementTeacher,
      String replacementSubject) {
    LessonOccurrence planned =
        new LessonOccurrence(WEEK_START.plusDays(1), 3L, subjectId, teacherId, roomId, groupId);
    LessonOccurrence effective =
        new LessonOccurrence(WEEK_START.plusDays(1), 3L, subjectId, teacherId, roomId, groupId);
    return new TeacherSubstitution(
        LessonChangeContext.matched(planned, effective), replacementTeacher, replacementSubject);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClockConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(FIRST_FETCH);
    }

    @Bean
    PersistenceRollbackProbe persistenceRollbackProbe(ActiveChangeStore store) {
      return new PersistenceRollbackProbe(store);
    }
  }

  static class PersistenceRollbackProbe {

    private final ActiveChangeStore store;

    PersistenceRollbackProbe(ActiveChangeStore store) {
      this.store = store;
    }

    @Transactional
    public void replaceWithInvalidFingerprint(TrackingScope scope, Instant attemptedAt) {
      TrackingState previous = store.lockOrCreate(scope);
      ActiveChangeState active = previous.activeChanges().getFirst();
      ActiveChangeState invalidReplacement =
          new ActiveChangeState(
              active.changeKey(),
              "f".repeat(65),
              active.metadata(),
              active.firstSeenAt(),
              attemptedAt);
      store.save(new TrackingState(scope, true, attemptedAt, List.of(invalidReplacement)));
    }
  }

  static final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void setInstant(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("Test clock only supports UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}

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
import java.sql.Date;
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
import org.springframework.beans.factory.annotation.Qualifier;
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
  @Autowired private FailingTrackingEventOutbox failingOutbox;

  private long vulcanAccountId;
  private long catalogClassId;

  private final SemanticChangeHasher hasher = new SemanticChangeHasher();

  @BeforeEach
  void clearDatabase() {
    jdbc.update("DELETE FROM notification_outbox");
    jdbc.update("DELETE FROM schedule_change_state");
    jdbc.update("DELETE FROM tracking_scope");
    jdbc.update("DELETE FROM monitoring_subscription");
    jdbc.update("DELETE FROM vulcan_class_catalog");
    jdbc.update("DELETE FROM vulcan_account_secret");
    jdbc.update("DELETE FROM vulcan_connect_token");
    jdbc.update("DELETE FROM vulcan_account");
    jdbc.update("DELETE FROM telegram_identity");
    jdbc.update("DELETE FROM app_user");
    long appUserId =
        jdbc.queryForObject(
            """
            INSERT INTO app_user (active, created_at, updated_at)
            VALUES (TRUE, ?, ?)
            RETURNING id
            """,
            Long.class,
            Timestamp.from(FIRST_FETCH),
            Timestamp.from(FIRST_FETCH));
    vulcanAccountId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_account
              (app_user_id, status, remember_credentials, created_at, updated_at, authenticated_at)
            VALUES (?, 'CONNECTED', FALSE, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            appUserId,
            Timestamp.from(FIRST_FETCH),
            Timestamp.from(FIRST_FETCH),
            Timestamp.from(FIRST_FETCH));
    catalogClassId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_class_catalog
              (vulcan_account_id, journal_id, class_id, name, school_year, active, synced_at)
            VALUES (?, ?, ?, 'Synthetic 2A', 2026, TRUE, ?)
            RETURNING id
            """,
            Long.class,
            vulcanAccountId,
            JOURNAL_ID,
            420L,
            Timestamp.from(FIRST_FETCH));
    jdbc.update(
        """
        INSERT INTO monitoring_subscription
          (app_user_id, catalog_class_id, enabled, created_at, updated_at)
        VALUES (?, ?, TRUE, ?, ?)
        """,
        appUserId,
        catalogClassId,
        Timestamp.from(FIRST_FETCH),
        Timestamp.from(FIRST_FETCH));
    failingOutbox.reset();
  }

  @Test
  void emptySuccessfulSnapshotEstablishesBaseline() {
    TrackingResult result = trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot());

    assertThat(result.baselineEstablishedNow()).isTrue();
    assertThat(result.activeChangeCount()).isZero();
    assertThat(result.transitions()).isEmpty();
    assertThat(
            jdbc.queryForObject("SELECT baseline_established FROM tracking_scope", Boolean.class))
        .isTrue();
    assertThat(timestamp("SELECT last_success_at FROM tracking_scope")).isEqualTo(FIRST_FETCH);
    assertThat(
            jdbc.queryForMap(
                "SELECT event_type, active_change_count, created_at FROM notification_outbox"))
        .containsEntry("event_type", "BASELINE_ESTABLISHED")
        .containsEntry("active_change_count", 0)
        .containsEntry("created_at", Timestamp.from(FIRST_FETCH));
  }

  @Test
  void existingChangesEstablishBaselineWithoutNewTransitions() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");

    TrackingResult result =
        trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(existing));

    assertThat(result.baselineEstablishedNow()).isTrue();
    assertThat(result.activeChangeCount()).isOne();
    assertThat(result.transitions()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
    assertThat(jdbc.queryForList("SELECT event_type FROM notification_outbox", String.class))
        .containsExactly("BASELINE_ESTABLISHED");
    assertThat(
            jdbc.queryForObject(
                "SELECT active_change_count FROM notification_outbox", Integer.class))
        .isOne();
  }

  @Test
  void unchangedChangeUpdatesLastSeenAndPreservesFirstSeen() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(existing));

    TrackingResult result =
        trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(existing));

    assertThat(result.baselineEstablishedNow()).isFalse();
    assertThat(result.transitions()).isEmpty();
    assertThat(timestamp("SELECT first_seen_at FROM schedule_change_state")).isEqualTo(FIRST_FETCH);
    assertThat(timestamp("SELECT last_seen_at FROM schedule_change_state")).isEqualTo(SECOND_FETCH);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
        .isOne();
  }

  @Test
  void newlyAppearingChangeIsPersistedAndEmitsNew() {
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot());
    ScheduleChange appeared = substitution(10L, 20L, 30L, 40L, "T2", "S2");

    TrackingResult result =
        trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(appeared));

    assertThat(result.transitions())
        .singleElement()
        .satisfies(
            transition -> {
              assertThat(transition.lifecycle()).isEqualTo(ChangeLifecycle.NEW);
              assertThat(transition.currentChange()).contains(appeared);
            });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
    assertLatestOutboxEvent("CHANGE_NEW", SECOND_FETCH);
  }

  @Test
  void changedContentUpdatesFingerprintWithoutDuplicatingActiveState() {
    ScheduleChange original = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    ScheduleChange updated = substitution(10L, 20L, 30L, 40L, "T3", "S3");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(original));

    TrackingResult result =
        trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(updated));

    assertThat(result.transitions())
        .singleElement()
        .satisfies(
            transition -> assertThat(transition.lifecycle()).isEqualTo(ChangeLifecycle.UPDATED));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
    assertThat(jdbc.queryForObject("SELECT fingerprint FROM schedule_change_state", String.class))
        .isEqualTo(hasher.fingerprint(updated));
    assertThat(timestamp("SELECT first_seen_at FROM schedule_change_state")).isEqualTo(FIRST_FETCH);
    assertLatestOutboxEvent("CHANGE_UPDATED", SECOND_FETCH);
  }

  @Test
  void absentChangeResolvesIsRemovedAndReappearanceBecomesNew() {
    ScheduleChange change = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(change));

    TrackingResult resolved =
        trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot());

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
    assertLatestOutboxEvent("CHANGE_RESOLVED", SECOND_FETCH);

    TrackingResult reappeared =
        trackerAt(THIRD_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(change));
    assertThat(reappeared.transitions())
        .extracting(ChangeTransition::lifecycle)
        .containsExactly(ChangeLifecycle.NEW);
    assertThat(timestamp("SELECT first_seen_at FROM schedule_change_state")).isEqualTo(THIRD_FETCH);
  }

  @Test
  void sameSlotDifferentGroupsRemainIndependent() {
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot());
    ScheduleChange firstGroup = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    ScheduleChange secondGroup = substitution(10L, 20L, 30L, 41L, "T2", "S2");

    TrackingResult result =
        trackerAt(SECOND_FETCH)
            .reconcileSuccessfulSnapshot(scope(), snapshot(firstGroup, secondGroup));

    assertThat(result.transitions())
        .hasSize(2)
        .allSatisfy(
            transition -> assertThat(transition.lifecycle()).isEqualTo(ChangeLifecycle.NEW));
    assertThat(
            jdbc.queryForList(
                "SELECT group_id FROM schedule_change_state ORDER BY group_id", Long.class))
        .containsExactly(40L, 41L);
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM notification_outbox WHERE event_type <> 'BASELINE_ESTABLISHED' ORDER BY id",
                String.class))
        .containsExactly("CHANGE_NEW", "CHANGE_NEW");
  }

  @Test
  void multipleIndependentChangesReconcileTogether() {
    ScheduleChange unchanged = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    ScheduleChange beforeUpdate = substitution(10L, 20L, 30L, 41L, "T2", "S2");
    ScheduleChange resolved = substitution(10L, 20L, 30L, 42L, "T2", "S2");
    trackerAt(FIRST_FETCH)
        .reconcileSuccessfulSnapshot(scope(), snapshot(unchanged, beforeUpdate, resolved));

    ScheduleChange updated = substitution(10L, 20L, 30L, 41L, "T3", "S3");
    ScheduleChange appeared = substitution(10L, 20L, 30L, 43L, "T2", "S2");
    TrackingResult result =
        trackerAt(SECOND_FETCH)
            .reconcileSuccessfulSnapshot(scope(), snapshot(unchanged, updated, appeared));

    assertThat(result.transitions())
        .extracting(ChangeTransition::lifecycle)
        .containsExactly(ChangeLifecycle.UPDATED, ChangeLifecycle.NEW, ChangeLifecycle.RESOLVED);
    assertThat(
            jdbc.queryForList(
                "SELECT group_id FROM schedule_change_state ORDER BY group_id", Long.class))
        .containsExactly(40L, 41L, 43L);
    assertThat(timestamp("SELECT last_success_at FROM tracking_scope")).isEqualTo(SECOND_FETCH);
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM notification_outbox WHERE event_type <> 'BASELINE_ESTABLISHED' ORDER BY id",
                String.class))
        .containsExactly("CHANGE_UPDATED", "CHANGE_NEW", "CHANGE_RESOLVED");
  }

  @Test
  void duplicateSemanticInputFailsBeforeMutationAndPreservesExistingState() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(existing));
    Map<String, Object> before = databaseState();

    assertThatThrownBy(
            () ->
                trackerAt(SECOND_FETCH)
                    .reconcileSuccessfulSnapshot(scope(), snapshot(existing, existing)))
        .isInstanceOf(DuplicateSemanticChangeKeyException.class)
        .hasMessageContaining("duplicate semantic change key")
        .hasMessageNotContaining("T2")
        .hasMessageNotContaining("S2");

    assertThat(databaseState()).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
        .isOne();
  }

  @Test
  void persistenceFailureAfterFlushedReplacementWorkRollsBackEntireTransaction() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(existing));
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
  void outboxFailureAfterRealInsertRollsBackTrackingAndNotificationIntentTogether() {
    ScheduleChange original = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    ScheduleChange updated = substitution(10L, 20L, 30L, 40L, "T3", "S3");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(original));
    Map<String, Object> before = databaseState();
    failingOutbox.failAfterNextDelegate();

    assertThatThrownBy(
            () -> trackerAt(SECOND_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(updated)))
        .isInstanceOf(SyntheticOutboxFailure.class);

    assertThat(databaseState()).isEqualTo(before);
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM notification_outbox ORDER BY id", String.class))
        .containsExactly("BASELINE_ESTABLISHED");
  }

  @Test
  void failedFetchNeverInvokesSuccessfulReconciliationOrResolvesActiveState() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(existing));
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
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
        .isOne();
  }

  @Test
  void exhaustedScheduledRefreshLeavesPostgresStateUnchangedAndEmitsNoResolvedTransition() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(existing));
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
            () -> List.of(new MonitoringTarget(vulcanAccountId, catalogClassId, JOURNAL_ID)),
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
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
        .isOne();
  }

  @Test
  void databaseEnforcesUniqueScopeAndActiveChangeKeys() {
    ScheduleChange existing = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(existing));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO tracking_scope
                      (catalog_class_id, journal_id, week_start, week_end,
                       baseline_established, last_success_at)
                    VALUES (?, ?, ?, ?, TRUE, ?)
                    """,
                    catalogClassId,
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
  void sameJournalAcrossAccountsAndLegacyHistoryRemainIndependent() {
    long ownerA =
        jdbc.queryForObject(
            "SELECT app_user_id FROM vulcan_account WHERE id = ?", Long.class, vulcanAccountId);
    long legacyScopeId =
        jdbc.queryForObject(
            """
            INSERT INTO tracking_scope
              (journal_id, week_start, week_end, baseline_established, last_success_at, version)
            VALUES (?, ?, ?, TRUE, ?, 0)
            RETURNING id
            """,
            Long.class,
            JOURNAL_ID,
            WEEK_START,
            WEEK_END,
            Timestamp.from(FIRST_FETCH));
    jdbc.update(
        """
        INSERT INTO schedule_change_state
          (scope_id, change_key, fingerprint, change_type, lesson_date,
           lesson_period_id, first_seen_at, last_seen_at)
        VALUES (?, ?, ?, 'TEACHER_SUBSTITUTION', ?, 3, ?, ?)
        """,
        legacyScopeId,
        "a".repeat(64),
        "b".repeat(64),
        WEEK_START.plusDays(1),
        Timestamp.from(FIRST_FETCH),
        Timestamp.from(FIRST_FETCH));
    long[] second = createConnectedCatalogUser(JOURNAL_ID, "Synthetic 2B");
    TrackingScope scopeA = scope();
    TrackingScope scopeB =
        new TrackingScope(second[1], second[2], JOURNAL_ID, WEEK_START, WEEK_END);
    ScheduleChange changeA = substitution(10L, 20L, 30L, 40L, "T2", "S2");
    ScheduleChange changeB = substitution(10L, 20L, 30L, 41L, "T2", "S2");

    TrackingResult baselineA =
        trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scopeA, snapshot(changeA));
    TrackingResult baselineB =
        trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scopeB, snapshot(changeB));
    TrackingResult updatedA =
        trackerAt(SECOND_FETCH)
            .reconcileSuccessfulSnapshot(
                scopeA, snapshot(substitution(10L, 20L, 30L, 40L, "T3", "S3")));

    assertThat(baselineA.baselineEstablishedNow()).isTrue();
    assertThat(baselineB.baselineEstablishedNow()).isTrue();
    assertThat(baselineA.transitions()).isEmpty();
    assertThat(baselineB.transitions()).isEmpty();
    assertThat(updatedA.transitions())
        .extracting(ChangeTransition::lifecycle)
        .containsExactly(ChangeLifecycle.UPDATED);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking_scope", Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForList(
                "SELECT catalog_class_id FROM tracking_scope ORDER BY catalog_class_id NULLS FIRST",
                Long.class))
        .containsExactly(null, catalogClassId, second[2]);
    assertThat(
            jdbc.queryForObject(
                "SELECT fingerprint FROM schedule_change_state WHERE scope_id = ?",
                String.class,
                legacyScopeId))
        .isEqualTo("b".repeat(64));
    assertThat(
            jdbc.queryForList(
                """
                SELECT catalog_class_id || ':' || recipient_user_id
                FROM notification_outbox
                ORDER BY id
                """,
                String.class))
        .containsExactly(
            catalogClassId + ":" + ownerA,
            second[2] + ":" + second[0],
            catalogClassId + ":" + ownerA);
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
    trackerAt(FIRST_FETCH).reconcileSuccessfulSnapshot(scope(), snapshot(existing));

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

    List<String> outboxColumns =
        jdbc.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'notification_outbox'
            ORDER BY ordinal_position
            """,
            String.class);
    assertThat(outboxColumns)
        .containsExactly(
            "id",
            "event_type",
            "journal_id",
            "week_start",
            "week_end",
            "active_change_count",
            "change_key",
            "change_type",
            "lesson_date",
            "lesson_period_id",
            "group_id",
            "subject_id",
            "status",
            "attempt_count",
            "next_attempt_at",
            "lease_until",
            "claim_token",
            "created_at",
            "delivered_at",
            "last_failure_category",
            "recipient_user_id",
            "catalog_class_id")
        .doesNotContain(
            "raw_annotation",
            "teacher_id",
            "teacher_name",
            "cookies",
            "token",
            "response_body",
            "snapshot_json");
  }

  private ScheduleChangeTracker trackerAt(Instant instant) {
    clock.setInstant(instant);
    return tracker;
  }

  private Instant timestamp(String sql) {
    return jdbc.queryForObject(sql, Timestamp.class).toInstant();
  }

  private void assertLatestOutboxEvent(String eventType, Instant createdAt) {
    assertThat(
            jdbc.queryForMap(
                """
                SELECT event_type, journal_id, week_start, week_end, change_key, change_type,
                       lesson_date, lesson_period_id, group_id, subject_id, created_at
                FROM notification_outbox ORDER BY id DESC LIMIT 1
                """))
        .containsEntry("event_type", eventType)
        .containsEntry("journal_id", JOURNAL_ID)
        .containsEntry("week_start", Date.valueOf(WEEK_START))
        .containsEntry("week_end", Date.valueOf(WEEK_END))
        .containsEntry("change_type", "TEACHER_SUBSTITUTION")
        .containsEntry("lesson_period_id", 3L)
        .containsEntry("group_id", 40L)
        .containsEntry("subject_id", 10L)
        .containsEntry("created_at", Timestamp.from(createdAt));
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

  private TrackingScope scope() {
    return new TrackingScope(vulcanAccountId, catalogClassId, JOURNAL_ID, WEEK_START, WEEK_END);
  }

  private long[] createConnectedCatalogUser(long journalId, String className) {
    long userId =
        jdbc.queryForObject(
            """
            INSERT INTO app_user (active, created_at, updated_at)
            VALUES (TRUE, ?, ?)
            RETURNING id
            """,
            Long.class,
            Timestamp.from(FIRST_FETCH),
            Timestamp.from(FIRST_FETCH));
    long accountId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_account
              (app_user_id, status, remember_credentials, created_at, updated_at, authenticated_at)
            VALUES (?, 'CONNECTED', FALSE, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            userId,
            Timestamp.from(FIRST_FETCH),
            Timestamp.from(FIRST_FETCH),
            Timestamp.from(FIRST_FETCH));
    long catalogId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_class_catalog
              (vulcan_account_id, journal_id, class_id, name, school_year, active, synced_at)
            VALUES (?, ?, ?, ?, 2026, TRUE, ?)
            RETURNING id
            """,
            Long.class,
            accountId,
            journalId,
            journalId + 1000,
            className,
            Timestamp.from(FIRST_FETCH));
    jdbc.update(
        """
        INSERT INTO monitoring_subscription
          (app_user_id, catalog_class_id, enabled, created_at, updated_at)
        VALUES (?, ?, TRUE, ?, ?)
        """,
        userId,
        catalogId,
        Timestamp.from(FIRST_FETCH),
        Timestamp.from(FIRST_FETCH));
    return new long[] {userId, accountId, catalogId};
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

    @Bean
    @Primary
    FailingTrackingEventOutbox failingTrackingEventOutbox(
        @Qualifier("jpaTrackingEventOutbox") TrackingEventOutbox delegate) {
      return new FailingTrackingEventOutbox(delegate);
    }
  }

  static final class FailingTrackingEventOutbox implements TrackingEventOutbox {

    private final TrackingEventOutbox delegate;
    private boolean failAfterDelegate;

    FailingTrackingEventOutbox(TrackingEventOutbox delegate) {
      this.delegate = delegate;
    }

    void failAfterNextDelegate() {
      failAfterDelegate = true;
    }

    void reset() {
      failAfterDelegate = false;
    }

    @Override
    public void recordReconciliation(
        TrackingScope scope, TrackingResult result, Instant occurredAt) {
      delegate.recordReconciliation(scope, result, occurredAt);
      if (failAfterDelegate) {
        failAfterDelegate = false;
        throw new SyntheticOutboxFailure();
      }
    }
  }

  static final class SyntheticOutboxFailure extends RuntimeException {}

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

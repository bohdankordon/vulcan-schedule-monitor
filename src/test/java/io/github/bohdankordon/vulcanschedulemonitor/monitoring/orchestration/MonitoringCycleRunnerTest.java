package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ActiveChangeStore;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleChangeTracker;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleRefreshCoordinator;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.SemanticChangeHasher;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingState;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.LessonChangeContext;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.TeacherSubstitution;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.LessonOccurrence;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MonitoringCycleRunnerTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC);
  private static final Duration SPACING = Duration.ofMillis(500);

  @Test
  void duplicateTargetsRunOneSortedPairEachWithCurrentWeekFirst() {
    List<TrackingScope> fetched = new ArrayList<>();
    RecordingDelay delays = new RecordingDelay();
    WeeklyScheduleSource source =
        scope -> {
          fetched.add(scope);
          return snapshot(scope);
        };

    MonitoringCycleSummary summary =
        runner(
                List.of(new MonitoringTarget(9), new MonitoringTarget(2), new MonitoringTarget(9)),
                source,
                new InMemoryStore(),
                delays)
            .runCycle();

    assertThat(summary.targetCount()).isEqualTo(2);
    assertThat(summary.plannedScopeCount()).isEqualTo(4);
    assertThat(fetched).extracting(TrackingScope::journalId).containsExactly(2L, 2L, 9L, 9L);
    assertThat(fetched)
        .extracting(TrackingScope::weekStart)
        .containsExactly(
            java.time.LocalDate.of(2026, 9, 7),
            java.time.LocalDate.of(2026, 9, 14),
            java.time.LocalDate.of(2026, 9, 7),
            java.time.LocalDate.of(2026, 9, 14));
    assertThat(delays.values).containsExactly(SPACING, SPACING, SPACING);
  }

  @Test
  void successfulSnapshotsReachTrackingAndCaptureBaselineThenTransitions() {
    InMemoryStore store = new InMemoryStore();
    AtomicInteger cycle = new AtomicInteger();
    WeeklyScheduleSource source =
        scope -> cycle.get() == 0 ? snapshot(scope) : snapshot(scope, substitution(scope));
    MonitoringCycleRunner runner =
        runner(List.of(new MonitoringTarget(42)), source, store, new RecordingDelay());

    MonitoringCycleSummary baseline = runner.runCycle();
    cycle.incrementAndGet();
    MonitoringCycleSummary changed = runner.runCycle();

    assertThat(baseline.outcomes())
        .extracting(ScopeMonitoringOutcome::category)
        .containsExactly(
            MonitoringOutcomeCategory.BASELINE_ESTABLISHED,
            MonitoringOutcomeCategory.BASELINE_ESTABLISHED);
    assertThat(changed.outcomes())
        .extracting(ScopeMonitoringOutcome::category)
        .containsExactly(
            MonitoringOutcomeCategory.TRANSITIONS, MonitoringOutcomeCategory.TRANSITIONS);
    assertThat(changed.outcomes())
        .extracting(ScopeMonitoringOutcome::transitionCount)
        .containsExactly(1, 1);
    assertThat(store.states).hasSize(2);
  }

  @Test
  void permanentAndProtocolFailuresRemainIsolatedAndLaterScopesRun() {
    AtomicInteger calls = new AtomicInteger();
    WeeklyScheduleSource source =
        scope -> {
          int call = calls.getAndIncrement();
          if (call == 0) {
            throw ScheduleSourceException.of(SourceFailureKind.PERMANENT_FAILURE);
          }
          if (call == 1) {
            throw ScheduleSourceException.of(SourceFailureKind.PROTOCOL_FAILURE);
          }
          return snapshot(scope);
        };

    MonitoringCycleSummary summary =
        runner(
                List.of(new MonitoringTarget(2), new MonitoringTarget(9)),
                source,
                new InMemoryStore(),
                new RecordingDelay())
            .runCycle();

    assertThat(calls).hasValue(4);
    assertThat(summary.outcomes())
        .extracting(ScopeMonitoringOutcome::category)
        .containsExactly(
            MonitoringOutcomeCategory.PERMANENT_FAILURE,
            MonitoringOutcomeCategory.PROTOCOL_FAILURE,
            MonitoringOutcomeCategory.BASELINE_ESTABLISHED,
            MonitoringOutcomeCategory.BASELINE_ESTABLISHED);
  }

  @Test
  void authenticationFailureStopsAllRemainingSharedSessionWork() {
    assertStopsAfterFirst(
        SourceFailureKind.AUTHENTICATION_REQUIRED,
        MonitoringOutcomeCategory.AUTHENTICATION_REQUIRED);
  }

  @Test
  void rateLimitDeferralStopsAllRemainingWork() {
    assertStopsAfterFirst(
        SourceFailureKind.DEFERRED_RATE_LIMIT, MonitoringOutcomeCategory.DEFERRED_RATE_LIMIT);
  }

  @Test
  void failedScopeNeverMutatesTrackingState() {
    InMemoryStore store = new InMemoryStore();
    WeeklyScheduleSource source =
        ignored -> {
          throw ScheduleSourceException.of(SourceFailureKind.TRANSIENT_FAILURE_EXHAUSTED);
        };

    MonitoringCycleSummary summary =
        runner(List.of(new MonitoringTarget(42)), source, store, new RecordingDelay()).runCycle();

    assertThat(summary.outcomes())
        .allSatisfy(
            outcome ->
                assertThat(outcome.category())
                    .isEqualTo(MonitoringOutcomeCategory.TRANSIENT_FAILURE_EXHAUSTED));
    assertThat(summary.outcomes())
        .extracting(ScopeMonitoringOutcome::transitionCount)
        .containsOnly(0);
    assertThat(store.states).isEmpty();
    assertThat(store.saveCount).isZero();
  }

  @Test
  void zeroTargetsPerformNoSourceWorkAndNoPacing() {
    AtomicInteger calls = new AtomicInteger();
    RecordingDelay delays = new RecordingDelay();
    MonitoringCycleSummary summary =
        runner(
                List.of(),
                scope -> {
                  calls.incrementAndGet();
                  return snapshot(scope);
                },
                new InMemoryStore(),
                delays)
            .runCycle();

    assertThat(summary.plannedScopeCount()).isZero();
    assertThat(summary.outcomes()).isEmpty();
    assertThat(calls).hasValue(0);
    assertThat(delays.values).isEmpty();
  }

  private static void assertStopsAfterFirst(
      SourceFailureKind failureKind, MonitoringOutcomeCategory expectedCategory) {
    AtomicInteger calls = new AtomicInteger();
    WeeklyScheduleSource source =
        ignored -> {
          calls.incrementAndGet();
          if (failureKind == SourceFailureKind.DEFERRED_RATE_LIMIT) {
            throw ScheduleSourceException.deferred(CLOCK.instant().plusSeconds(60));
          }
          throw ScheduleSourceException.of(failureKind);
        };

    MonitoringCycleSummary summary =
        runner(
                List.of(new MonitoringTarget(2), new MonitoringTarget(9)),
                source,
                new InMemoryStore(),
                new RecordingDelay())
            .runCycle();

    assertThat(calls).hasValue(1);
    assertThat(summary.stoppedEarly()).isTrue();
    assertThat(summary.outcomes())
        .singleElement()
        .extracting(ScopeMonitoringOutcome::category)
        .isEqualTo(expectedCategory);
  }

  private static MonitoringCycleRunner runner(
      Collection<MonitoringTarget> targets,
      WeeklyScheduleSource source,
      InMemoryStore store,
      DelayStrategy delay) {
    ScheduleChangeTracker tracker =
        new ScheduleChangeTracker(store, new SemanticChangeHasher(), CLOCK);
    return new MonitoringCycleRunner(
        () -> targets,
        new MonitoringScopePlanner(CLOCK),
        new ScheduleRefreshCoordinator(source, tracker),
        delay,
        SPACING,
        CLOCK);
  }

  private static ScheduleSnapshot snapshot(TrackingScope scope, TeacherSubstitution... changes) {
    return new ScheduleSnapshot(
        scope.journalId(), scope.weekStart(), scope.weekEnd(), List.of(), List.of(changes));
  }

  private static TeacherSubstitution substitution(TrackingScope scope) {
    LessonOccurrence occurrence =
        new LessonOccurrence(scope.weekStart().plusDays(1), 3L, 10L, 20L, 30L, 40L);
    return new TeacherSubstitution(LessonChangeContext.matched(occurrence, occurrence), "T2", "S2");
  }

  private static final class RecordingDelay implements DelayStrategy {

    private final List<Duration> values = new ArrayList<>();

    @Override
    public void delay(Duration duration) {
      values.add(duration);
    }
  }

  private static final class InMemoryStore implements ActiveChangeStore {

    private final Map<TrackingScope, TrackingState> states = new HashMap<>();
    private int saveCount;

    @Override
    public TrackingState lockOrCreate(TrackingScope scope) {
      return states.getOrDefault(scope, new TrackingState(scope, false, null, List.of()));
    }

    @Override
    public void save(TrackingState state) {
      states.put(state.scope(), state);
      saveCount++;
    }
  }
}
